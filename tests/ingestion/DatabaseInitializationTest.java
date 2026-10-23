import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INGESTION_DB_TEST", matches="true")
class DatabaseInitializationTest {
    @TempDir Path temporary;
    String name, url;

    @BeforeEach void freshDatabase() throws Exception {
        String base = System.getenv().getOrDefault("DB_URL", "");
        assertTrue(base.endsWith("/ingestion_test"), "Tests require the dedicated ingestion_test database");
        name = "initialization_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DatabaseStore.connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        url = base.substring(0, base.lastIndexOf('/') + 1) + name;
    }

    @AfterEach void removeOwnDatabase() throws Exception {
        if (url == null) return;
        try (var connection = DatabaseStore.connect(); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + name);
        }
    }

    MappingPlan mapping() throws Exception { return MappingPlan.load(MappingPlan.DEFAULT); }

    boolean exists(Connection connection, String relation) throws Exception {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, relation);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }

    void sentinel(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("INSERT INTO ingestion.batches VALUES ('00000000-0000-0000-0000-000000000001','source','schema','mapping',1,0,0,now())");
        }
    }

    void assertSentinel(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM ingestion.batches WHERE source_sha256='source'")) {
            rows.next(); assertEquals(1, rows.getLong(1));
        }
    }

    @Test void createsTablesAndRepeatsWithoutChangingDataOrRunningDdl() throws Exception {
        try (var connection = DatabaseStore.connect(url)) {
            var result = DatabaseInitializer.initialize(connection, mapping(), false);
            assertEquals(3, result.createdTables().size());
            DatabaseStore.eligible(connection);
            sentinel(connection);
            // A compatible existing schema needs no CREATE/ALTER permissions or writes.
            connection.setReadOnly(true);
            var repeated = DatabaseInitializer.initialize(connection, mapping(), false);
            assertTrue(repeated.createdTables().isEmpty());
            assertSentinel(connection);
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test void createsMissingTablesAndPreservesExistingTablesAndRows() throws Exception {
        try (var connection = DatabaseStore.connect(url)) {
            DatabaseInitializer.initialize(connection, mapping(), false);
            sentinel(connection);
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE ingestion.invoice_lines");
                statement.execute("DROP TABLE ingestion.invoices");
            }
            var result = DatabaseInitializer.initialize(connection, mapping(), false);
            assertEquals(java.util.List.of("ingestion.invoices", "ingestion.invoice_lines"), result.createdTables());
            DatabaseStore.eligible(connection);
            assertSentinel(connection);
        }
    }

    @Test void dryRunRollsBackAllCreatedObjects() throws Exception {
        try (var connection = DatabaseStore.connect(url)) {
            var result = DatabaseInitializer.initialize(connection, mapping(), true);
            assertTrue(result.dryRun()); assertEquals(3, result.createdTables().size());
            assertFalse(exists(connection, "ingestion.batches"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname='ingestion'")) {
                rows.next(); assertEquals(0, rows.getLong(1));
            }
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test void incompatiblePartialSchemaRollsBackNewTablesAndKeepsExistingData() throws Exception {
        try (var connection = DatabaseStore.connect(url)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA ingestion");
                statement.execute("CREATE TABLE ingestion.invoices (invoice_id text)");
                statement.execute("INSERT INTO ingestion.invoices VALUES ('preserve me')");
            }
            assertThrows(Exception.class, () -> DatabaseInitializer.initialize(connection, mapping(), false));
            assertFalse(exists(connection, "ingestion.batches"));
            assertFalse(exists(connection, "ingestion.invoice_lines"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT invoice_id FROM ingestion.invoices")) {
                assertTrue(rows.next()); assertEquals("preserve me", rows.getString(1));
            }
        }
    }

    @Test void incompatibleCompleteSchemaIsRejectedWithoutChangingIt() throws Exception {
        try (var connection = DatabaseStore.connect(url)) {
            DatabaseInitializer.initialize(connection, mapping(), false);
            sentinel(connection);
            try (var statement = connection.createStatement()) { statement.execute("ALTER TABLE ingestion.invoices ADD COLUMN extra text"); }
            String before = DatabaseStore.contract(connection);
            assertThrows(IllegalArgumentException.class, () -> DatabaseInitializer.initialize(connection, mapping(), false));
            assertEquals(before, DatabaseStore.contract(connection));
            assertSentinel(connection);
        }
    }

    @Test void unsupportedMappingFingerprintCannotCreateTables() throws Exception {
        var yaml = MappingPlanTest.yaml(); yaml.put("database_sha256", "0".repeat(64));
        Path file = temporary.resolve("wrong.yaml");
        Files.writeString(file, new org.yaml.snakeyaml.Yaml().dump(yaml));
        try (var connection = DatabaseStore.connect(url)) {
            assertThrows(IllegalArgumentException.class, () -> DatabaseInitializer.initialize(connection, MappingPlan.load(file), false));
            assertFalse(exists(connection, "ingestion.batches"));
        }
    }

    @Test void sqlFailureLeavesDatabaseUnchanged() throws Exception {
        try (var connection = DatabaseStore.connect(url)) {
            connection.setReadOnly(true);
            assertThrows(java.sql.SQLException.class, () -> DatabaseInitializer.initialize(connection, mapping(), false));
            assertFalse(exists(connection, "ingestion.batches"));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test void exportedScriptInitializesAndRepeatsWithoutChangingData() throws Exception {
        Path file = temporary.resolve("initialize.sql");
        DatabaseInitializer.writeScript(mapping(), file);
        String sql = Files.readString(file);
        try (var connection = DatabaseStore.connect(url); var statement = connection.createStatement()) {
            statement.execute(sql);
            DatabaseStore.eligible(connection);
            sentinel(connection);
            statement.execute(sql);
            DatabaseStore.eligible(connection);
            assertSentinel(connection);
        }
    }

    @Test void exportedScriptRejectsIncompatibleSchemaAndRollsBackNewTables() throws Exception {
        Path file = temporary.resolve("initialize.sql");
        DatabaseInitializer.writeScript(mapping(), file);
        try (var connection = DatabaseStore.connect(url); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA ingestion");
            statement.execute("CREATE TABLE ingestion.unexpected (value text)");
            statement.execute("INSERT INTO ingestion.unexpected VALUES ('preserve me')");
            assertThrows(java.sql.SQLException.class, () -> statement.execute(Files.readString(file)));
            statement.execute("ROLLBACK");
            assertFalse(exists(connection, "ingestion.batches"));
            try (var rows = statement.executeQuery("SELECT value FROM ingestion.unexpected")) {
                assertTrue(rows.next()); assertEquals("preserve me", rows.getString(1));
            }
        }
    }
}
