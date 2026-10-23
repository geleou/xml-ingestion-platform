import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.SchemaFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Fixture check only; this is not the production validator or schema resolver. */
public class VerifySamples {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length == 0 ? "samples" : args[0]).toAbsolutePath();
        int failures = 0;
        for (String domain : new String[] {"invoices", "telemetry", "registry"}) {
            var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            // Generated trusted local fixtures use relative imports and includes.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            var schema = factory.newSchema(base.resolve(domain).resolve("schema.xsd").toFile());
            for (String variant : new String[] {"valid", "schema-invalid", "malformed"}) {
                var parserFactory = SAXParserFactory.newInstance();
                parserFactory.setNamespaceAware(true);
                parserFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                var reader = parserFactory.newSAXParser().getXMLReader();
                reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
                reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                var errors = new DefaultHandler() {
                    String actual = "valid";
                    int line = -1;
                    @Override public void error(SAXParseException e) {
                        actual = "schema-invalid";
                        if (line == -1) line = e.getLineNumber();
                    }
                    @Override public void fatalError(SAXParseException e) throws SAXParseException {
                        actual = "malformed";
                        line = e.getLineNumber();
                        throw e;
                    }
                };
                reader.setErrorHandler(errors);
                var validator = schema.newValidator();
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                validator.setErrorHandler(errors);
                try {
                    validator.validate(new SAXSource(reader, new InputSource(
                        base.resolve(domain).resolve(variant + ".xml").toUri().toString())));
                } catch (SAXParseException e) {
                    if (!errors.actual.equals("malformed")) throw e;
                }
                boolean passed = variant.equals(errors.actual);
                if (!passed) failures++;
                System.out.printf("%s %s/%s.xml: %s (line %d)%n",
                    passed ? "PASS" : "FAIL", domain, variant, errors.actual, errors.line);
            }
        }
        if (failures != 0) throw new AssertionError(failures + " unexpected validation outcomes");
    }
}
