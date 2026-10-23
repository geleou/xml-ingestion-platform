import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class XmlIngestionCliTest {
    @TempDir Path temporary;
    record Run(int code, String output, String error) {}

    static Run run(String... args) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code;
        try (var out = new PrintStream(output, true, StandardCharsets.UTF_8);
             var err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            code = XmlIngestionCli.run(args, out, err);
        }
        return new Run(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    Path schema() throws Exception {
        Path xsd = temporary.resolve("rules with spaces.xsd");
        Files.writeString(xsd, """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="https://example.test/ns/cli" elementFormDefault="qualified">
              <xs:element name="Root"><xs:complexType><xs:sequence>
                <xs:element name="Value" type="xs:nonNegativeInteger"/>
              </xs:sequence><xs:attribute name="version" type="xs:positiveInteger"/></xs:complexType></xs:element>
            </xs:schema>
            """);
        return xsd;
    }

    Path xml(String filename, String value) throws Exception {
        Path xml = temporary.resolve(filename);
        Files.writeString(xml, "<Root xmlns='https://example.test/ns/cli'><Value>" + value + "</Value></Root>");
        return xml;
    }

    @Test void helpForAllCommandsDoesNotRequireInputsOrDatabase() {
        assertEquals(0, run("--help").code());
        for (String command : List.of("validate", "validate-mapping", "generate-mapping", "generate-sql", "initialize-database", "ingest")) {
            Run result = run(command, "--help");
            assertEquals(0, result.code()); assertTrue(result.output().contains(command));
        }
    }

    @Test void invalidUsageReturnsTwo() {
        for (String[] args : List.of(new String[] {}, new String[] {"unknown"}, new String[] {"validate"},
            new String[] {"validate", "--xsd"}, new String[] {"validate", "--unknown", "value"},
            new String[] {"validate", "--xsd", "a", "--xsd", "b", "input.xml"},
            new String[] {"generate-mapping", "--xsd", "a", "--database=true"},
            new String[] {"generate-mapping", "--xsd", "a", "unexpected"},
            new String[] {"ingest", "--xsd", "a", "--mapping", "b"})) {
            assertEquals(2, run(args).code(), java.util.Arrays.toString(args));
        }
    }

    @Test void batchValidationContinuesAfterInvalidAndMalformedDocuments() throws Exception {
        Path xsd = schema(), good = xml("valid.xml", "42"), bad = xml("invalid.xml", "-1");
        Path malformed = temporary.resolve("malformed.xml");
        Files.writeString(malformed, "<Root xmlns='https://example.test/ns/cli'><Value>1</Broken></Root>");
        Run result = run("validate", "--xsd", xsd.toString(), bad.toString(), malformed.toString(), good.toString());
        assertEquals(1, result.code());
        assertTrue(result.output().contains("INVALID_XML: " + bad));
        assertTrue(result.output().contains("INVALID_XML: " + malformed));
        assertTrue(result.output().contains("VALID: " + good));
        assertTrue(result.output().contains("1 passed, 2 failed, 3 total"));
        assertTrue(result.error().contains(":1:"));
    }

    @Test void repeatedXmlFlagsAndEqualsOptionsWork() throws Exception {
        Path xsd = schema(), first = xml("first.xml", "1"), second = xml("second.xml", "2");
        Run result = run("validate", "--xsd=" + xsd, "--xml", first.toString(), "--xml=" + second);
        assertEquals(0, result.code()); assertTrue(result.output().contains("2 passed, 0 failed, 2 total"));
    }

    @Test void missingFileDoesNotPreventLaterFilesBeingValidated() throws Exception {
        Path xsd = schema(), good = xml("valid.xml", "1");
        Run result = run("validate", "--xsd", xsd.toString(), temporary.resolve("missing.xml").toString(), good.toString());
        assertEquals(1, result.code()); assertTrue(result.output().contains("1 passed, 1 failed, 2 total"));
    }

    @Test void dashDelimiterTreatsFollowingArgumentsAsFiles() throws Exception {
        Path xsd = schema();
        Run result = run("validate", "--xsd", xsd.toString(), "--", "--not-an-option.xml");
        assertEquals(1, result.code()); assertFalse(result.error().contains("USAGE ERROR"));
    }

    @Test void generatesFromXsdAloneAndCoversAttributesWithNamespaceUrisContainingSlashes() throws Exception {
        Path xsd = schema(), output = temporary.resolve("draft.yaml");
        Run result = run("generate-mapping", "--xsd", xsd.toString(), "--output", output.toString());
        assertEquals(0, result.code(), result.error());
        MappingPlan mapping = MappingPlan.load(output);
        mapping.coverage(XsdFields.inspect(xsd, temporary));
        assertEquals(2, mapping.fields.size());
        assertEquals("unbound", mapping.databaseHash);
        assertEquals("unconfigured", mapping.adapter);
        for (var field : mapping.fields) for (String target : field.targets()) {
            assertTrue(target.matches("ingestion\\.[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*"));
            for (String identifier : target.split("\\.")) assertTrue(identifier.length() <= 63);
        }
        assertTrue(result.output().contains("XSD only"));
    }

    @Test void defaultOutputIsBesideXsdAndExistingFilesArePreserved() throws Exception {
        Path xsd = schema();
        Path output = temporary.resolve("rules with spaces.mapping.yaml");
        assertEquals(0, run("generate-mapping", "--xsd", xsd.toString()).code());
        String before = Files.readString(output);
        assertEquals(1, run("generate-mapping", "--xsd", xsd.toString()).code());
        assertEquals(before, Files.readString(output));
    }

    @Test void invoiceTemplateCanBeGeneratedWithoutDatabase() throws Exception {
        Path output = temporary.resolve("invoice.yaml");
        Run result = run("generate-mapping", "--xsd", "samples/invoices/schema.xsd", "--schema-root", "samples", "--output", output.toString());
        assertEquals(0, result.code(), result.error());
        MappingPlan mapping = MappingPlan.load(output);
        mapping.coverage(MappingPlanTest.inventory());
        mapping.invoiceAdapter();
        assertEquals(DatabaseStore.reviewedInvoiceContract(), mapping.databaseHash);
    }

    @Test void invalidXsdDoesNotProduceAMapping() throws Exception {
        Path xsd = temporary.resolve("invalid.xsd"), output = temporary.resolve("draft.yaml");
        Files.writeString(xsd, "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'><xs:element name='Root' type='xs:nonexistent'/></xs:schema>");
        Run result = run("generate-mapping", "--xsd", xsd.toString(), "--output", output.toString());
        assertEquals(1, result.code()); assertFalse(Files.exists(output));
        assertTrue(result.error().contains("Invalid XSD"));
    }

    @Test void missingBindingIsRejectedByMappingCommandBeforeDatabaseAccess() throws Exception {
        var yaml = MappingPlanTest.yaml(); MappingPlanTest.fields(yaml).remove(0);
        Path mapping = temporary.resolve("incomplete.yaml");
        Files.writeString(mapping, new org.yaml.snakeyaml.Yaml().dump(yaml));
        Run result = run("validate-mapping", "--xsd", "samples/invoices/schema.xsd", "--schema-root", "samples", "--mapping", mapping.toString());
        assertEquals(1, result.code()); assertTrue(result.error().contains("Missing XSD field"));
    }

    @Test void unboundDraftCannotBeMistakenForAnInsertableMapping() throws Exception {
        Path xsd = schema(), output = temporary.resolve("draft.yaml");
        assertEquals(0, run("generate-mapping", "--xsd", xsd.toString(), "--output", output.toString()).code());
        Run result = run("validate-mapping", "--xsd", xsd.toString(), "--mapping", output.toString());
        assertEquals(1, result.code()); assertFalse(result.output().contains("MAPPING ELIGIBLE"));
    }

    @Test void initializationRequiresMappingButNotXsdAndRejectsUnboundDraftOffline() throws Exception {
        assertEquals(2, run("initialize-database").code());
        assertEquals(2, run("initialize-database", "--mapping", MappingPlan.DEFAULT.toString(), "--schema-root", "samples").code());
        assertEquals(2, run("initialize-database", "--mapping", MappingPlan.DEFAULT.toString(), "--dry-run=true").code());
        Path xsd = schema(), mapping = temporary.resolve("draft.yaml");
        assertEquals(0, run("generate-mapping", "--xsd", xsd.toString(), "--output", mapping.toString()).code());
        var result = run("initialize-database", "--mapping", mapping.toString());
        assertEquals(1, result.code()); assertTrue(result.error().contains("No executable adapter"));
    }

    @Test void exportsSqlFromMappingWithoutDatabaseAndPreservesExistingOutput() throws Exception {
        Path output = temporary.resolve("initialize.sql");
        var result = run("generate-sql", "--mapping", MappingPlan.DEFAULT.toString(), "--output", output.toString());
        assertEquals(0, result.code(), result.error());
        String sql = Files.readString(output);
        assertTrue(sql.contains("BEGIN;")); assertTrue(sql.endsWith("COMMIT;\n"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS ingestion.invoices"));
        assertTrue(sql.contains("FOREIGN KEY")); assertTrue(sql.contains("PRIMARY KEY"));
        assertTrue(sql.contains(MappingPlan.load(MappingPlan.DEFAULT).sha256));
        assertTrue(sql.contains("RAISE EXCEPTION"));
        assertEquals(1, run("generate-sql", "--mapping", MappingPlan.DEFAULT.toString(), "--output", output.toString()).code());
        assertEquals(sql, Files.readString(output));
    }

    @Test void sqlExportChecksRequiredArgumentsAndRejectsUnsupportedMappings() throws Exception {
        assertEquals(2, run("generate-sql", "--mapping", MappingPlan.DEFAULT.toString()).code());
        Path output = temporary.resolve("init.sql"), mapping = temporary.resolve("draft.yaml"), xsd = schema();
        assertEquals(0, run("generate-mapping", "--xsd", xsd.toString(), "--output", mapping.toString()).code());
        assertEquals(1, run("generate-sql", "--mapping", mapping.toString(), "--output", output.toString()).code());
        assertFalse(Files.exists(output));
    }
}
