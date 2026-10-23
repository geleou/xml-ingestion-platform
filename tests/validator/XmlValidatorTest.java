import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Dependency-free checks for validation and the local schema security boundary. */
public class XmlValidatorTest {
    private static int checks;

    private static void expect(XmlValidator.Outcome outcome, Path xml, Path xsd, Path root) {
        var result = XmlValidator.validate(xml, xsd, root);
        if (result.outcome() != outcome) throw new AssertionError(result);
        if (result.valid() != (outcome == XmlValidator.Outcome.VALID)) throw new AssertionError(result);
        if (outcome != XmlValidator.Outcome.VALID && result.diagnostics().isEmpty()) throw new AssertionError(result);
        checks++;
    }

    private static Path write(Path root, String name, String content) throws Exception {
        Path path = root.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("xml-validator-test-");
        try {
            Path root = Files.createDirectory(sandbox.resolve("trusted"));
            String prefix = "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">";
            String suffix = "</xs:schema>";
            String definition = "<xs:simpleType name=\"Amount\"><xs:restriction base=\"xs:integer\">"
                + "<xs:minInclusive value=\"0\"/></xs:restriction></xs:simpleType>";
            write(root, "types.xsd", prefix + definition + suffix);
            Path xsd = write(root, "schema.xsd", prefix + "<xs:include schemaLocation=\"types.xsd\"/>"
                + "<xs:element name=\"Batch\"><xs:complexType><xs:sequence>"
                + "<xs:element name=\"Amount\" type=\"Amount\" maxOccurs=\"unbounded\"/>"
                + "</xs:sequence></xs:complexType></xs:element>" + suffix);
            Path xml = write(root, "input.xml", "<Batch><Amount>12</Amount></Batch>");
            expect(XmlValidator.Outcome.VALID, xml, xsd, root);
            if (!XmlValidator.validate(xml, xsd).valid()) throw new AssertionError("Default root failed");
            write(root, "input.xml", "<Batch>\n<Amount>-1</Amount>\n</Batch>");
            expect(XmlValidator.Outcome.INVALID_XML, xml, xsd, root);
            if (XmlValidator.validate(xml, xsd, root).diagnostics().get(0).line() != 2)
                throw new AssertionError("Missing error location");
            write(root, "input.xml", "<Batch><Amount>12</Broken></Batch>");
            expect(XmlValidator.Outcome.INVALID_XML, xml, xsd, root);
            write(root, "input.xml", "<Batch xmlns=\"urn:wrong\"><Amount>12</Amount></Batch>");
            expect(XmlValidator.Outcome.INVALID_XML, xml, xsd, root);
            write(root, "input.xml", "<!DOCTYPE Batch [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<Batch><Amount>&xxe;</Amount></Batch>");
            expect(XmlValidator.Outcome.INVALID_XML, xml, xsd, root);
            write(root, "input.xml", "<Batch>" + "<Amount>bad</Amount>".repeat(100) + "</Batch>");
            var bounded = XmlValidator.validate(xml, xsd, root);
            if (bounded.outcome() != XmlValidator.Outcome.INVALID_XML
                || bounded.diagnostics().size() != XmlValidator.MAX_DIAGNOSTICS) throw new AssertionError(bounded);
            checks++;
            write(root, "input.xml", "<Batch><Amount>12</Amount></Batch>");
            Path network = write(root, "network.xsd", prefix
                + "<xs:include schemaLocation=\"https://example.invalid/schema.xsd\"/>" + suffix);
            expect(XmlValidator.Outcome.INVALID_SCHEMA, xml, network, root);
            write(sandbox, "outside.xsd", prefix + definition + suffix);
            Path traversal = write(root, "traversal.xsd", prefix
                + "<xs:include schemaLocation=\"../outside.xsd\"/>" + suffix);
            expect(XmlValidator.Outcome.INVALID_SCHEMA, xml, traversal, root);
            Files.createSymbolicLink(root.resolve("link.xsd"), sandbox.resolve("outside.xsd"));
            Path symlink = write(root, "symlink.xsd", prefix + "<xs:include schemaLocation=\"link.xsd\"/>" + suffix);
            expect(XmlValidator.Outcome.INVALID_SCHEMA, xml, symlink, root);
            expect(XmlValidator.Outcome.INVALID_SCHEMA, xml, sandbox.resolve("outside.xsd"), root);
            Path broken = write(root, "broken.xsd", prefix + "<xs:element" + suffix);
            expect(XmlValidator.Outcome.INVALID_SCHEMA, xml, broken, root);
            expect(XmlValidator.Outcome.ERROR, root.resolve("missing.xml"), xsd, root);
            System.out.println("PASS: " + checks + " validator checks, plus default-root and location checks");
        } finally {
            try (var paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
