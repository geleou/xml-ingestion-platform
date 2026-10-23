import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import org.postgresql.PGConnection;

/** PostgreSQL eligibility checks and transactional staging/publication. */
public final class DatabaseStore {
    // Fingerprint of the reviewed DDL's live catalog representation (PostgreSQL 17).
    private static final String CONTRACT = "79a1f5fbdf90f0107c07433590911b1401d59ee6146b4a213df00b53fdce82c8";

    /** Expected invoice DDL identity, also available to the database-free draft generator. */
    public static String reviewedInvoiceContract() { return CONTRACT; }

    public static Connection connect() throws Exception {
        return connect(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://127.0.0.1:55432/ingestion"));
    }

    public static Connection connect(String jdbcUrl) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", System.getenv().getOrDefault("DB_USER", "ingestion"));
        String password = System.getenv("DB_PASSWORD");
        if (password == null) password = Files.readString(Path.of(
            System.getenv().getOrDefault("DB_PASSWORD_FILE", "secrets/db_password"))).stripTrailing();
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10");
        Connection connection = DriverManager.getConnection(jdbcUrl, properties);
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO pg_catalog");
        } catch (SQLException e) {
            try { connection.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
        return connection;
    }

    public static String contract(Connection connection) throws Exception {
        return contract(connection, "ingestion");
    }

    public static String contract(Connection connection, String schema) throws Exception {
        String sql = contractQuery();
        StringBuilder signature = new StringBuilder();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) signature.append(rows.getString(1)).append('\n');
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(signature.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Shared catalog query for Java preflight and standalone SQL initialization scripts. */
    public static String contractQuery() {
        return """
            WITH tables AS (
              SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
              WHERE n.nspname=? AND c.relkind IN ('r','p','v','f')
            ), lines AS (
              SELECT 'table|'||relname||'|'||relkind::text||'|'||relrowsecurity||'|'||relforcerowsecurity AS line FROM tables
              UNION ALL
              SELECT 'column|'||t.relname||'|'||a.attnum||'|'||a.attname||'|'||format_type(a.atttypid,a.atttypmod)
                ||'|'||a.attnotnull||'|'||a.attidentity::text||'|'||a.attgenerated::text||'|'||coalesce(pg_get_expr(d.adbin,d.adrelid),'')
              FROM tables t JOIN pg_attribute a ON a.attrelid=t.oid
              LEFT JOIN pg_attrdef d ON d.adrelid=t.oid AND d.adnum=a.attnum
              WHERE a.attnum>0 AND NOT a.attisdropped
              UNION ALL
              SELECT 'constraint|'||t.relname||'|'||c.conname||'|'||pg_get_constraintdef(c.oid,true)
              FROM tables t JOIN pg_constraint c ON c.conrelid=t.oid
              UNION ALL
              SELECT 'index|'||t.relname||'|'||pg_get_indexdef(i.indexrelid)
              FROM tables t JOIN pg_index i ON i.indrelid=t.oid
              UNION ALL
              SELECT 'trigger|'||t.relname||'|'||pg_get_triggerdef(g.oid)
              FROM tables t JOIN pg_trigger g ON g.tgrelid=t.oid WHERE NOT g.tgisinternal
            ) SELECT line FROM lines ORDER BY line COLLATE "C"
            """;
    }

    public static void eligible(Connection connection) throws Exception {
        String actual = contract(connection);
        if (!CONTRACT.equals(actual)) throw new IllegalArgumentException(
            "Database schema is not eligible for " + InvoiceMapping.VERSION + "; reviewed DDL contract does not match (actual " + actual + ")");
    }

    public record Imported(UUID batch, boolean alreadyImported) {}

    public static Imported publish(Connection connection, UUID batch, String sourceHash, String schemaHash,
                                   InvoiceExtractor.Extraction extraction) throws Exception {
        return publish(connection, batch, sourceHash, schemaHash, extraction, MappingPlan.load(MappingPlan.DEFAULT));
    }

    public static Imported publish(Connection connection, UUID batch, String sourceHash, String schemaHash,
                                   InvoiceExtractor.Extraction extraction, MappingPlan mapping) throws Exception {
        mapping.invoiceAdapter();
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("Publication needs a fresh connection");
        if (!InvoiceMapping.sha256(extraction.invoices()).equals(extraction.invoiceHash())
            || !InvoiceMapping.sha256(extraction.lines()).equals(extraction.lineHash())) {
            throw new IllegalArgumentException("Extracted CSV changed before publication");
        }
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.createStatement()) {
                // Prevent concurrent ALTER/DROP while verifying and publishing; other imports may run.
                statement.execute("LOCK TABLE ingestion.batches, ingestion.invoices, ingestion.invoice_lines IN ROW EXCLUSIVE MODE");
            }
            eligible(connection);
            mapping.database(connection);
            try (var insert = connection.prepareStatement("""
                INSERT INTO ingestion.batches
                  (batch_id,source_sha256,schema_sha256,mapping_version,document_version,invoice_count,line_count)
                VALUES (?,?,?,?,?::numeric,?,?)
                ON CONFLICT (source_sha256,schema_sha256,mapping_version) DO NOTHING
                RETURNING batch_id
                """)) {
                insert.setObject(1, batch); insert.setString(2, sourceHash); insert.setString(3, schemaHash);
                insert.setString(4, mapping.identity()); insert.setString(5, extraction.documentVersion());
                insert.setLong(6, extraction.invoiceCount()); insert.setLong(7, extraction.lineCount());
                try (var rows = insert.executeQuery()) {
                    if (!rows.next()) {
                        try (var select = connection.prepareStatement("SELECT batch_id FROM ingestion.batches WHERE source_sha256=? AND schema_sha256=? AND mapping_version=?")) {
                            select.setString(1, sourceHash); select.setString(2, schemaHash); select.setString(3, mapping.identity());
                            try (var existing = select.executeQuery()) {
                                if (!existing.next()) throw new SQLException("Concurrent import result was not found");
                                UUID previous = existing.getObject(1, UUID.class);
                                connection.commit();
                                return new Imported(previous, true);
                            }
                        }
                    }
                }
            }
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE stage_invoices (LIKE ingestion.invoices) ON COMMIT DROP");
                statement.execute("CREATE TEMP TABLE stage_lines (LIKE ingestion.invoice_lines) ON COMMIT DROP");
            }
            var copy = connection.unwrap(PGConnection.class).getCopyAPI();
            long invoices, lines;
            try (var input = Files.newBufferedReader(extraction.invoices(), StandardCharsets.UTF_8)) {
                invoices = copy.copyIn("COPY pg_temp.stage_invoices (batch_id,invoice_id,classification,customer_name,country) FROM STDIN WITH (FORMAT csv)", input);
            }
            try (var input = Files.newBufferedReader(extraction.lines(), StandardCharsets.UTF_8)) {
                lines = copy.copyIn("COPY pg_temp.stage_lines (batch_id,invoice_id,line_no,sku,quantity,unit_price) FROM STDIN WITH (FORMAT csv)", input);
            }
            if (invoices != extraction.invoiceCount() || lines != extraction.lineCount()) throw new SQLException("COPY row counts differ from extraction");
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO ingestion.invoices SELECT * FROM pg_temp.stage_invoices");
                statement.executeUpdate("INSERT INTO ingestion.invoice_lines SELECT * FROM pg_temp.stage_lines");
            }
            connection.commit();
            return new Imported(batch, false);
        } catch (Exception e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e;
        } finally { connection.setAutoCommit(true); }
    }
}
