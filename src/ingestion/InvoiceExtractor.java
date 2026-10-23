import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Streams a validated invoice document to two CSV files, without database writes. */
public final class InvoiceExtractor {
    public record Extraction(Path invoices, Path lines, String invoiceHash, String lineHash,
                             long invoiceCount, long lineCount, String documentVersion) {}

    public static Extraction extract(Path validatedXml, UUID batch, Path directory) throws Exception {
        Path invoices = directory.resolve("invoices.csv");
        Path lines = directory.resolve("invoice-lines.csv");
        var factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities forbidden"); });
        try (var invoiceWriter = Files.newBufferedWriter(invoices, StandardCharsets.UTF_8);
             var lineWriter = Files.newBufferedWriter(lines, StandardCharsets.UTF_8);
             var input = Files.newInputStream(validatedXml)) {
            var handler = new Handler(batch, invoiceWriter, lineWriter);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(input));
            invoiceWriter.flush();
            lineWriter.flush();
            return new Extraction(invoices, lines, InvoiceMapping.sha256(invoices), InvoiceMapping.sha256(lines),
                handler.invoiceCount, handler.lineCount, handler.documentVersion);
        }
    }

    private static final class Handler extends DefaultHandler {
        private static final int MAX_FIELD_CHARS = 1_048_576;
        private static final String BASE = "Invoices/Invoice/";
        final UUID batch;
        final BufferedWriter invoices, lines;
        final List<String> elements = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        boolean collecting;
        String id, classification, name, country, sku, quantity, price, documentVersion;
        long invoiceCount, lineCount, lineNumber;
        Handler(UUID batch, BufferedWriter invoices, BufferedWriter lines) {
            this.batch = batch; this.invoices = invoices; this.lines = lines;
        }
        @Override public void startElement(String uri, String local, String qname, Attributes attrs) throws SAXException {
            if (!InvoiceMapping.NAMESPACE.equals(uri)) throw new SAXException("Unsupported element namespace: " + uri);
            elements.add(local);
            String path = String.join("/", elements);
            if (path.equals("Invoices")) documentVersion = number(attrs.getValue("version"), true);
            if (path.equals("Invoices/Invoice")) {
                id = classification = name = country = null;
                lineNumber = 0;
            }
            if (path.equals(BASE + "Lines/Line")) sku = quantity = price = null;
            collecting = switch (path) {
                case BASE + "InvoiceId", BASE + "Classification", BASE + "Customer/Name", BASE + "Customer/Country",
                     BASE + "Lines/Line/Sku", BASE + "Lines/Line/Quantity", BASE + "Lines/Line/UnitPrice" -> true;
                default -> false;
            };
            text.setLength(0);
        }
        @Override public void characters(char[] chars, int start, int length) throws SAXException {
            if (!collecting) return;
            if (text.length() + length > MAX_FIELD_CHARS) throw new SAXException("Mapping field exceeds 1,048,576-character limit");
            text.append(chars, start, length);
        }
        @Override public void endElement(String uri, String local, String qname) throws SAXException {
            String path = String.join("/", elements);
            String value = text.toString();
            switch (path) {
                case BASE + "InvoiceId" -> id = number(value, true);
                case BASE + "Classification" -> classification = value;
                case BASE + "Customer/Name" -> name = token(value);
                case BASE + "Customer/Country" -> country = value;
                case BASE + "Lines/Line/Sku" -> sku = token(value);
                case BASE + "Lines/Line/Quantity" -> quantity = number(value, true);
                case BASE + "Lines/Line/UnitPrice" -> price = number(value, false);
                case BASE + "Lines/Line" -> {
                    csv(lines, batch.toString(), id, Long.toString(++lineNumber), sku, quantity, price);
                    lineCount++;
                }
                case "Invoices/Invoice" -> {
                    csv(invoices, batch.toString(), id, classification, name, country);
                    invoiceCount++;
                }
                default -> { }
            }
            elements.remove(elements.size() - 1);
            collecting = false;
            text.setLength(0);
        }
        @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
    }

    private static String token(String text) { return text.replaceAll("[\\t\\n\\r ]+", " ").trim(); }

    private static String number(String text, boolean integer) throws SAXException {
        try {
            if (text == null || text.trim().length() > 1000) throw new NumberFormatException("Numeric field exceeds 1,000 characters");
            String normalized = text.trim();
            if (integer) {
                BigInteger value = new BigInteger(normalized);
                if (value.signum() <= 0) throw new NumberFormatException("Expected positive integer");
                return value.toString();
            }
            BigDecimal value = new BigDecimal(normalized);
            if (value.signum() < 0) throw new NumberFormatException("Expected nonnegative decimal");
            return value.toPlainString();
        } catch (NumberFormatException e) { throw new SAXException("Numeric value cannot be ingested: " + e.getMessage(), e); }
    }

    private static void csv(BufferedWriter writer, String... fields) throws SAXException {
        try {
            for (int i = 0; i < fields.length; i++) {
                if (fields[i] == null) throw new SAXException("Missing mapped field; extract only validated invoice XML");
                if (i != 0) writer.write(',');
                writer.write('"');
                writer.write(fields[i].replace("\"", "\"\""));
                writer.write('"');
            }
            writer.newLine();
        } catch (IOException e) { throw new SAXException(e); }
    }
}
