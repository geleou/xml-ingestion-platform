import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Apply only the adapter's reviewed CREATE statements, transactionally and idempotently. */
public final class DatabaseInitializer {
    private static final List<String> TABLES = List.of("batches", "invoices", "invoice_lines");
    public record Initialized(List<String> createdTables, boolean dryRun) {
        public Initialized { createdTables = List.copyOf(createdTables); }
    }

    /** Reject unsupported/unbound mappings before opening a connection or running SQL. */
    public static void supported(MappingPlan mapping) {
        mapping.invoiceAdapter();
        if (!mapping.databaseHash.equals(DatabaseStore.reviewedInvoiceContract())) {
            throw new IllegalArgumentException("Mapping has no supported initialization SQL for its database fingerprint");
        }
    }

    /** Export the same reviewed DDL with transaction and live-schema compatibility guards. */
    public static void writeScript(MappingPlan mapping, Path output) throws Exception {
        supported(mapping);
        String catalog = DatabaseStore.contractQuery().replace("?", "'ingestion'");
        String script = "-- Generated initialization SQL for mapping " + mapping.identity() + "\n"
            + "-- PostgreSQL 17; run with psql -v ON_ERROR_STOP=1. No XML data is imported.\n"
            + "BEGIN;\nSET LOCAL search_path TO pg_catalog;\nSET LOCAL lock_timeout = '10s';\n"
            + "SELECT pg_advisory_xact_lock(1481460809, 1);\n\n"
            + String.join(";\n\n", statements()) + ";\n\n"
            + "LOCK TABLE ingestion.batches, ingestion.invoices, ingestion.invoice_lines IN SHARE ROW EXCLUSIVE MODE;\n"
            + "DO $mapping_contract$\nDECLARE actual text;\nBEGIN\n"
            + "  SELECT encode(sha256(convert_to(coalesce(string_agg(line, E'\\n' ORDER BY line COLLATE \"C\") || E'\\n', ''), 'UTF8')), 'hex')\n"
            + "  INTO actual FROM (\n" + catalog + ") AS schema_signature;\n"
            + "  IF actual <> '" + mapping.databaseHash + "' THEN\n"
            + "    RAISE EXCEPTION 'Database schema does not match mapping contract (actual %)', actual;\n"
            + "  END IF;\nEND;\n$mapping_contract$;\nCOMMIT;\n";
        Files.writeString(output, script, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    public static Initialized initialize(Connection connection, MappingPlan mapping, boolean dryRun) throws Exception {
        supported(mapping);
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("Initialization needs a fresh connection");
        List<String> sql = statements();
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '10s'");
                // Serialize this adapter's initializers within the target database.
                statement.execute("SELECT pg_advisory_xact_lock(1481460809, 1)");
            }
            Set<String> missing = new LinkedHashSet<>(TABLES);
            boolean schemaExists;
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='ingestion')")) {
                rows.next(); schemaExists = rows.getBoolean(1);
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='ingestion' AND c.relname IN ('batches','invoices','invoice_lines')
                """)) {
                while (rows.next()) missing.remove(rows.getString(1));
            }
            try (var statement = connection.createStatement()) {
                for (String command : sql) {
                    if (command.equals("CREATE SCHEMA IF NOT EXISTS ingestion")) {
                        if (!schemaExists) statement.execute(command);
                    } else {
                        for (String table : missing) {
                            if (command.startsWith("CREATE TABLE IF NOT EXISTS ingestion." + table + " (")) statement.execute(command);
                        }
                    }
                }
                // Keep the inspected tables stable until compatibility verification completes.
                statement.execute("LOCK TABLE ingestion.batches, ingestion.invoices, ingestion.invoice_lines IN "
                    + (missing.isEmpty() ? "ACCESS SHARE" : "SHARE ROW EXCLUSIVE") + " MODE");
            }
            DatabaseStore.eligible(connection);
            mapping.database(connection);
            if (dryRun) connection.rollback();
            else connection.commit();
            return new Initialized(missing.stream().map(name -> "ingestion." + name).toList(), dryRun);
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally { connection.setAutoCommit(true); }
    }

    private static List<String> statements() throws Exception {
        String ddl;
        try (var input = DatabaseInitializer.class.getResourceAsStream("/db/001-invoices.sql")) {
            if (input == null) throw new IllegalStateException("Bundled initialization SQL missing; rebuild with Maven");
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        // This bundled template contains only simple CREATE statements, not arbitrary SQL scripts.
        String text = String.join("\n", ddl.lines().filter(line -> !line.stripLeading().startsWith("--")).toList());
        List<String> commands = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String part : text.split(";")) {
            String command = part.strip();
            if (command.isEmpty()) continue;
            if (command.equals("CREATE SCHEMA ingestion") && seen.add("schema")) {
                commands.add("CREATE SCHEMA IF NOT EXISTS ingestion");
                continue;
            }
            boolean accepted = false;
            for (String table : TABLES) {
                String prefix = "CREATE TABLE ingestion." + table + " (";
                if (command.startsWith(prefix) && seen.add(table)) {
                    commands.add(command.replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"));
                    accepted = true;
                    break;
                }
            }
            if (!accepted) throw new IllegalStateException("Initialization template contains unsupported SQL");
        }
        if (!seen.equals(Set.of("schema", "batches", "invoices", "invoice_lines"))) {
            throw new IllegalStateException("Initialization template is incomplete");
        }
        return commands;
    }
}
