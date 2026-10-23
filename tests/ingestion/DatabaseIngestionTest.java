import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INGESTION_DB_TEST", matches="true")
class DatabaseIngestionTest {
    @TempDir Path temporary;

    @BeforeAll static void dedicatedDatabaseOnly() {
        assertTrue(System.getenv().getOrDefault("DB_URL", "").endsWith("/ingestion_test"),
            "Integration tests require the dedicated ingestion_test database");
    }

    private Path document(String text) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + ".xml");
        Files.writeString(file, text);
        return file;
    }

    private Ingestor.Receipt ingest(Path xml) throws Exception {
        return Ingestor.ingest(xml, Path.of("samples/invoices/schema.xsd"), Path.of("samples"), temporary.resolve("runs"));
    }

    private static String counts(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("""
            SELECT (SELECT count(*) FROM ingestion.batches),
                   (SELECT count(*) FROM ingestion.invoices),
                   (SELECT count(*) FROM ingestion.invoice_lines)
            """)) {
            result.next(); return result.getLong(1) + ":" + result.getLong(2) + ":" + result.getLong(3);
        }
    }

    @Test void importsAndDeduplicatesWithExactValues() throws Exception {
        Path xml = document(IngestionTest.xml(IngestionTest.invoice(17, "Company " + UUID.randomUUID())));
        var receipt = ingest(xml);
        try (var connection = DatabaseStore.connect()) {
            try {
                assertFalse(receipt.alreadyImported());
                assertEquals(MappingPlan.load(MappingPlan.DEFAULT).sha256,
                    InvoiceMapping.sha256(receipt.artifacts().resolve("mapping.yaml")));
                var duplicate = ingest(xml);
                assertTrue(duplicate.alreadyImported());
                assertEquals(receipt.batch(), duplicate.batch());
                try (var query = connection.prepareStatement("SELECT quantity,unit_price FROM ingestion.invoice_lines WHERE batch_id=? ORDER BY line_no")) {
                    query.setObject(1, receipt.batch());
                    try (var result = query.executeQuery()) {
                        assertTrue(result.next()); assertEquals("2", result.getString(1)); assertEquals("12.50", result.getString(2));
                        assertTrue(result.next()); assertEquals("3", result.getString(1)); assertEquals("0.125", result.getString(2));
                        assertFalse(result.next());
                    }
                }
            } finally {
                for (String table : new String[] {"invoice_lines", "invoices", "batches"}) {
                    try (var delete = connection.prepareStatement("DELETE FROM ingestion." + table + " WHERE batch_id=?")) {
                        delete.setObject(1, receipt.batch()); delete.executeUpdate();
                    }
                }
            }
        }
    }

    @Test void rejectsInvalidAndMalformedXmlWithoutPublishing() throws Exception {
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            String valid = IngestionTest.xml(IngestionTest.invoice(1, "Bad data"));
            assertThrows(IllegalArgumentException.class,
                () -> ingest(document(valid.replace("12.50", "-12.50"))));
            assertThrows(IllegalArgumentException.class,
                () -> ingest(document(valid.replace("</Invoice>", "</Broken>"))));
            assertEquals(before, counts(connection));
            try (var paths = Files.list(temporary.resolve("runs"))) {
                for (Path run : paths.toList()) assertFalse(Files.exists(run.resolve("invoices.csv")), "Invalid XML reached extraction");
            }
        }
    }

    @Test void duplicateInvoiceIdsRollbackEntireBatch() throws Exception {
        Path xml = document(IngestionTest.xml(IngestionTest.invoice(1, "First") + IngestionTest.invoice(1, "Duplicate")));
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            assertThrows(java.sql.SQLException.class, () -> ingest(xml));
            assertEquals(before, counts(connection));
        }
    }

    @Test void incompatibleDatabaseIsRejected() throws Exception {
        try (var connection = DatabaseStore.connect()) {
            connection.setAutoCommit(false);
            try {
                DatabaseStore.eligible(connection);
                try (var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE ingestion.invoices ADD COLUMN incompatible text");
                }
                assertThrows(IllegalArgumentException.class, () -> DatabaseStore.eligible(connection));
            } finally { connection.rollback(); }
        }
    }

    @Test void changedStagingFilesCannotBeImported() throws Exception {
        Path xml = document(IngestionTest.xml(IngestionTest.invoice(1, "Checksum")));
        UUID batch = UUID.randomUUID();
        var extraction = InvoiceExtractor.extract(xml, batch, temporary);
        Files.writeString(extraction.lines(), "tampered");
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            assertThrows(IllegalArgumentException.class,
                () -> DatabaseStore.publish(connection, batch, "test", "test", extraction));
            assertEquals(before, counts(connection));
        }
    }

    @Test void generatesCompleteInvoiceMappingWithoutChangingDatabase() throws Exception {
        Path generated = temporary.resolve("mapping.yaml");
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            assertEquals(0, MappingPlan.generate(Path.of("samples/invoices/schema.xsd"), Path.of("samples"), generated, connection, "ingestion"));
            MappingPlan mapping = MappingPlan.load(generated);
            mapping.coverage(MappingPlanTest.inventory());
            mapping.database(connection);
            mapping.invoiceAdapter();
            assertEquals(before, counts(connection));
            assertThrows(IllegalArgumentException.class,
                () -> MappingPlan.generate(Path.of("samples/invoices/schema.xsd"), Path.of("samples"), generated, connection, "ingestion"));
        }
    }

    @Test void generatorLeavesAmbiguousAndUnknownColumnsUnresolved() throws Exception {
        Path xsd = temporary.resolve("schema.xsd");
        Files.writeString(xsd, """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="Root"><xs:complexType><xs:sequence>
                <xs:element name="country" type="xs:string"/>
                <xs:element name="invoice_id" type="xs:positiveInteger"/>
                <xs:element name="unknown" type="xs:string"/>
              </xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """);
        Path output = temporary.resolve("generated.yaml");
        try (var connection = DatabaseStore.connect()) {
            assertEquals(2, MappingPlan.generate(xsd, temporary, output, connection, "ingestion"));
            MappingPlan mapping = MappingPlan.load(output);
            assertEquals(3, mapping.fields.size());
            assertEquals(java.util.List.of("ingestion.invoices.country"), mapping.fields.get(0).targets());
            assertTrue(mapping.fields.get(1).targets().isEmpty());
            assertTrue(mapping.fields.get(2).targets().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> mapping.coverage(XsdFields.inspect(xsd, temporary)));
            assertThrows(IllegalArgumentException.class, mapping::invoiceAdapter);
        }
    }

    @Test void rejectsMissingDatabaseColumnsAndIncompatibleTypes() throws Exception {
        var yaml = MappingPlanTest.yaml();
        MappingPlanTest.fields(yaml).get(0).put("targets", java.util.List.of("ingestion.invoices.missing_column"));
        Path file = temporary.resolve("mapping.yaml");
        Files.writeString(file, new org.yaml.snakeyaml.Yaml().dump(yaml));
        MappingPlan missing = MappingPlan.load(file);
        yaml = MappingPlanTest.yaml();
        MappingPlanTest.fields(yaml).get(0).put("targets", java.util.List.of("ingestion.invoices.customer_name"));
        Files.writeString(file, new org.yaml.snakeyaml.Yaml().dump(yaml));
        MappingPlan wrongType = MappingPlan.load(file);
        try (var connection = DatabaseStore.connect()) {
            assertThrows(IllegalArgumentException.class, () -> missing.database(connection));
            assertThrows(IllegalArgumentException.class, () -> wrongType.database(connection));
        }
    }

    @Test void generatorRejectsNarrowNumericAndStringColumns() throws Exception {
        Path xsd = temporary.resolve("schema.xsd");
        Files.writeString(xsd, """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="Root"><xs:complexType><xs:sequence>
                <xs:element name="price" type="xs:decimal"/>
                <xs:element name="name" type="xs:string"/>
              </xs:sequence></xs:complexType></xs:element>
            </xs:schema>
            """);
        String schema = "mapping_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DatabaseStore.connect()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA " + schema);
                    statement.execute("CREATE TABLE " + schema + ".target (price numeric(10,2), name varchar(20))");
                }
                assertEquals(2, MappingPlan.generate(xsd, temporary, temporary.resolve("draft.yaml"), connection, schema));
            } finally { connection.rollback(); }
        }
    }

    @Test void cliMappingValidationAndDatabaseGenerationPerformNoDataWrites() throws Exception {
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            var checked = XmlIngestionCliTest.run("validate-mapping", "--xsd", "samples/invoices/schema.xsd",
                "--schema-root", "samples", "--mapping", MappingPlan.DEFAULT.toString());
            assertEquals(0, checked.code(), checked.error());
            assertTrue(checked.output().contains("MAPPING ELIGIBLE"));
            Path generated = temporary.resolve("database.yaml");
            var result = XmlIngestionCliTest.run("generate-mapping", "--database", "--xsd", "samples/invoices/schema.xsd",
                "--schema-root", "samples", "--output", generated.toString());
            assertEquals(0, result.code(), result.error());
            MappingPlan.load(generated).database(connection);
            assertEquals(before, counts(connection));
        }
    }

    @Test void cliInitializationOfCompatibleDatabaseIsRepeatableAndSupportsDryRun() throws Exception {
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            var initialized = XmlIngestionCliTest.run("initialize-database", "--mapping", MappingPlan.DEFAULT.toString());
            assertEquals(0, initialized.code(), initialized.error());
            assertTrue(initialized.output().contains("ALREADY_INITIALIZED"));
            var checked = XmlIngestionCliTest.run("initialize-database", "--mapping", MappingPlan.DEFAULT.toString(), "--dry-run",
                "--xsd", "samples/invoices/schema.xsd", "--schema-root", "samples");
            assertEquals(0, checked.code(), checked.error());
            assertTrue(checked.output().contains("rolled back"));
            assertEquals(before, counts(connection));
        }
    }

    @Test void cliIngestsMultipleDocumentsAndContinuesAfterPerFileFailure() throws Exception {
        Path mapping = temporary.resolve("offline.yaml");
        assertEquals(0, XmlIngestionCliTest.run("generate-mapping", "--xsd", "samples/invoices/schema.xsd",
            "--schema-root", "samples", "--output", mapping.toString()).code());
        var checked = XmlIngestionCliTest.run("validate-mapping", "--xsd", "samples/invoices/schema.xsd",
            "--schema-root", "samples", "--mapping", mapping.toString());
        assertEquals(0, checked.code(), checked.error());
        Path first = document(IngestionTest.xml(IngestionTest.invoice(1, "First " + UUID.randomUUID())));
        Path invalid = document(IngestionTest.xml(IngestionTest.invoice(2, "Invalid")).replace("12.50", "-12.50"));
        Path second = document(IngestionTest.xml(IngestionTest.invoice(3, "Second " + UUID.randomUUID())));
        try (var connection = DatabaseStore.connect()) {
            String before = counts(connection);
            var result = XmlIngestionCliTest.run("ingest", "--xsd", "samples/invoices/schema.xsd", "--schema-root", "samples",
                "--mapping", mapping.toString(), "--work-dir", temporary.resolve("cli-runs").toString(),
                first.toString(), invalid.toString(), second.toString());
            var batchIds = new java.util.ArrayList<UUID>();
            var matcher = java.util.regex.Pattern.compile("batch=([0-9a-f-]{36})").matcher(result.output());
            while (matcher.find()) batchIds.add(UUID.fromString(matcher.group(1)));
            try {
                assertEquals(1, result.code(), result.error());
                assertTrue(result.output().contains("2 succeeded, 1 failed, 3 total"));
                assertEquals(2, batchIds.size(), result.output());
                for (UUID id : batchIds) {
                    try (var query = connection.prepareStatement("SELECT invoice_count,line_count FROM ingestion.batches WHERE batch_id=?")) {
                        query.setObject(1, id);
                        try (var rows = query.executeQuery()) {
                            assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); assertEquals(2, rows.getLong(2));
                        }
                    }
                }
            } finally {
                for (UUID id : batchIds) for (String table : new String[] {"invoice_lines", "invoices", "batches"}) {
                    try (var delete = connection.prepareStatement("DELETE FROM ingestion." + table + " WHERE batch_id=?")) {
                        delete.setObject(1, id); delete.executeUpdate();
                    }
                }
            }
            assertEquals(before, counts(connection));
        }
    }
}
