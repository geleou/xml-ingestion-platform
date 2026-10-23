import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Schema;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Standalone XSD 1.0 validator. Uses only the JDK; never builds an XML DOM. */
public final class XmlValidator {
    public static final int MAX_DIAGNOSTICS = 20;

    public enum Outcome { VALID, INVALID_XML, INVALID_SCHEMA, ERROR }

    public record Diagnostic(String message, String source, int line, int column) {}

    public record Result(Outcome outcome, Path schema, Duration elapsed,
                         List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
        public boolean valid() { return outcome == Outcome.VALID; }
    }

    /** By default, only dependencies inside the schema's own directory are trusted. */
    public static Result validate(Path xml, Path xsd) {
        return validate(xml, xsd, xsd.toAbsolutePath().getParent());
    }

    /** Check the XSD ruleset itself without needing an XML document. */
    public static Result validateSchema(Path xsd, Path schemaRoot) {
        long started = System.nanoTime();
        Path schemaPath = xsd.toAbsolutePath().normalize();
        Diagnostics diagnostics = new Diagnostics();
        Outcome outcome;
        try (LocalSchemas resolver = new LocalSchemas(schemaRoot)) {
            schemaPath = resolver.checked(schemaPath);
            compile(schemaPath, resolver, diagnostics);
            outcome = diagnostics.items.isEmpty() ? Outcome.VALID : Outcome.INVALID_SCHEMA;
        } catch (SAXException | IllegalArgumentException e) {
            diagnostics.addException(e);
            outcome = Outcome.INVALID_SCHEMA;
        } catch (IOException e) {
            diagnostics.addException(e);
            outcome = Outcome.ERROR;
        }
        return result(outcome, schemaPath, started, diagnostics);
    }

    private static Schema compile(Path path, LocalSchemas resolver, Diagnostics diagnostics) throws SAXException, IOException {
        var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver(resolver);
        factory.setErrorHandler(diagnostics);
        var source = new StreamSource(resolver.open(path));
        source.setSystemId(path.toUri().toString());
        return factory.newSchema(source);
    }

    /** schemaRoot must contain the root XSD and every imported/included XSD. */
    public static Result validate(Path xml, Path xsd, Path schemaRoot) {
        long started = System.nanoTime();
        Path schemaPath = xsd.toAbsolutePath().normalize();
        Diagnostics diagnostics = new Diagnostics();
        Outcome outcome;
        boolean compilingSchema = true;
        try (LocalSchemas resolver = new LocalSchemas(schemaRoot)) {
            schemaPath = resolver.checked(schemaPath);
            var schema = compile(schemaPath, resolver, diagnostics);
            if (!diagnostics.items.isEmpty()) {
                return result(Outcome.INVALID_SCHEMA, schemaPath, started, diagnostics);
            }

            compilingSchema = false;
            var parserFactory = SAXParserFactory.newInstance();
            parserFactory.setNamespaceAware(true);
            parserFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parserFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            parserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var reader = parserFactory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External XML entities are forbidden");
            });
            reader.setErrorHandler(diagnostics);
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // XML schemaLocation hints cannot trigger additional schema reads.
            validator.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
                throw new IllegalArgumentException("XML cannot request additional schemas: " + systemId);
            });
            validator.setErrorHandler(diagnostics);
            try (InputStream input = Files.newInputStream(xml)) {
                var document = new InputSource(input);
                document.setSystemId(xml.toAbsolutePath().normalize().toUri().toString());
                validator.validate(new SAXSource(reader, document));
            }
            outcome = diagnostics.items.isEmpty() ? Outcome.VALID : Outcome.INVALID_XML;
        } catch (SAXException | IllegalArgumentException e) {
            diagnostics.addException(e);
            outcome = compilingSchema ? Outcome.INVALID_SCHEMA : Outcome.INVALID_XML;
        } catch (IOException | ParserConfigurationException e) {
            diagnostics.addException(e);
            outcome = Outcome.ERROR;
        }
        return result(outcome, schemaPath, started, diagnostics);
    }

    private static Result result(Outcome outcome, Path schema, long started, Diagnostics errors) {
        return new Result(outcome, schema, Duration.ofNanos(System.nanoTime() - started), errors.items);
    }

    private static final class Diagnostics implements ErrorHandler {
        final List<Diagnostic> items = new ArrayList<>();
        @Override public void warning(SAXParseException e) { /* Warnings do not invalidate XML. */ }
        @Override public void error(SAXParseException e) throws SAXException {
            add(e);
            if (items.size() == MAX_DIAGNOSTICS) throw e;
        }
        @Override public void fatalError(SAXParseException e) throws SAXException {
            add(e);
            throw e;
        }
        private void add(SAXParseException e) {
            if (items.size() < MAX_DIAGNOSTICS) {
                items.add(new Diagnostic(e.getMessage(), e.getSystemId(), e.getLineNumber(), e.getColumnNumber()));
            }
        }
        void addException(Exception e) {
            if (!items.isEmpty()) return;
            if (e instanceof SAXParseException parsed) add(parsed);
            else items.add(new Diagnostic(e.getMessage(), null, -1, -1));
        }
    }

    /** Resolves only real, regular files beneath a caller-selected trusted root. */
    private static final class LocalSchemas implements LSResourceResolver, AutoCloseable {
        private final Path root;
        private final List<InputStream> streams = new ArrayList<>();
        LocalSchemas(Path root) throws IOException {
            this.root = root.toRealPath();
            if (!Files.isDirectory(this.root)) throw new IOException("Schema root must be a directory");
        }
        Path checked(Path path) throws IOException {
            Path real = path.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                throw new IllegalArgumentException("Schema outside trusted root or not a regular file: " + path);
            }
            return real;
        }
        InputStream open(Path path) throws IOException {
            InputStream stream = Files.newInputStream(checked(path));
            streams.add(stream);
            return stream;
        }
        @Override public LSInput resolveResource(String type, String namespace, String publicId,
                                                  String systemId, String baseUri) {
            if (!XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(type) || systemId == null || baseUri == null) {
                throw new IllegalArgumentException("Only explicitly located local XSD dependencies are allowed");
            }
            URI uri = URI.create(baseUri).resolve(systemId);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Non-file schema dependency forbidden: " + uri);
            }
            try {
                Path path = checked(Path.of(uri));
                var input = new SchemaInput();
                input.setPublicId(publicId);
                input.setSystemId(path.toUri().toString());
                input.setBaseURI(path.toUri().toString());
                input.setByteStream(open(path));
                return input;
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read schema dependency: " + uri, e);
            }
        }
        @Override public void close() throws IOException {
            IOException failure = null;
            for (InputStream stream : streams) {
                try { stream.close(); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class SchemaInput implements LSInput {
        private Reader characterStream;
        private InputStream byteStream;
        private String stringData, systemId, publicId, baseURI, encoding;
        private boolean certifiedText;
        public Reader getCharacterStream() { return characterStream; }
        public void setCharacterStream(Reader value) { characterStream = value; }
        public InputStream getByteStream() { return byteStream; }
        public void setByteStream(InputStream value) { byteStream = value; }
        public String getStringData() { return stringData; }
        public void setStringData(String value) { stringData = value; }
        public String getSystemId() { return systemId; }
        public void setSystemId(String value) { systemId = value; }
        public String getPublicId() { return publicId; }
        public void setPublicId(String value) { publicId = value; }
        public String getBaseURI() { return baseURI; }
        public void setBaseURI(String value) { baseURI = value; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String value) { encoding = value; }
        public boolean getCertifiedText() { return certifiedText; }
        public void setCertifiedText(boolean value) { certifiedText = value; }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: java src/validator/XmlValidator.java <xml> <xsd> [trusted-schema-root]");
            System.exit(2);
        }
        Result result = args.length == 3
            ? validate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]))
            : validate(Path.of(args[0]), Path.of(args[1]));
        System.out.println(String.format("%s: %s (schema: %s, elapsed: %d ms)",
            result.outcome(), args[0], result.schema(), result.elapsed().toMillis()));
        for (Diagnostic error : result.diagnostics()) {
            System.err.printf("%s:%d:%d: %s%n", error.source() == null ? "input" : error.source(),
                error.line(), error.column(), error.message());
        }
        System.exit(switch (result.outcome()) {
            case VALID -> 0;
            case INVALID_XML -> 1;
            case INVALID_SCHEMA, ERROR -> 2;
        });
    }
}
