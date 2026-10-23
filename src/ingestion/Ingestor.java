import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/** Synchronous first ingestion pipeline: eligibility -> validate -> extract -> publish. */
public final class Ingestor {
    public record Receipt(UUID batch, Path artifacts, boolean alreadyImported) {}
    public record Eligibility(MappingPlan mapping, InvoiceMapping.Bundle bundle) {}

    /** Shared preflight for the CLI and ingestion; performs no data writes. */
    public static Eligibility check(Path xsd, Path schemaRoot, Path mappingFile) throws Exception {
        MappingPlan mapping = MappingPlan.load(mappingFile);
        mapping.coverage(XsdFields.inspect(xsd, schemaRoot));
        mapping.invoiceAdapter();
        var bundle = InvoiceMapping.eligible(xsd, schemaRoot);
        try (var connection = DatabaseStore.connect()) {
            mapping.database(connection);
            DatabaseStore.eligible(connection);
        }
        return new Eligibility(mapping, bundle);
    }

    public static Receipt ingest(Path xml, Path xsd, Path schemaRoot, Path workRoot) throws Exception {
        return ingest(xml, xsd, schemaRoot, MappingPlan.DEFAULT, workRoot);
    }

    public static Receipt ingest(Path xml, Path xsd, Path schemaRoot, Path mappingFile, Path workRoot) throws Exception {
        Eligibility eligibility = check(xsd, schemaRoot, mappingFile);
        MappingPlan mapping = eligibility.mapping();
        var bundle = eligibility.bundle();
        UUID attempt = UUID.randomUUID();
        Files.createDirectories(workRoot);
        Path run = Files.createDirectory(workRoot.resolve(attempt.toString()));
        Properties manifest = new Properties();
        manifest.setProperty("attempt", attempt.toString());
        manifest.setProperty("mapping", mapping.identity());
        manifest.setProperty("mapping.sha256", mapping.sha256);
        manifest.setProperty("schema.sha256", bundle.sha256());
        manifest.setProperty("source.path", xml.toAbsolutePath().toString());
        boolean committed = false;
        try {
            status(run, manifest, "SNAPSHOTTING");
            mapping.snapshot(run.resolve("mapping.yaml"));
            var pinned = InvoiceMapping.snapshot(bundle, run.resolve("schemas"));
            mapping.coverage(XsdFields.inspect(pinned.xsd(), pinned.root()));
            Path snapshot = run.resolve("source.xml");
            Files.copy(xml, snapshot);
            String sourceHash = InvoiceMapping.sha256(snapshot);
            manifest.setProperty("source.sha256", sourceHash);
            var validation = XmlValidator.validate(snapshot, pinned.xsd(), pinned.root());
            if (!validation.valid()) throw new IllegalArgumentException("XML validation failed: " + validation.diagnostics());
            status(run, manifest, "VALIDATED");
            var extraction = InvoiceExtractor.extract(snapshot, attempt, run);
            if (!sourceHash.equals(InvoiceMapping.sha256(snapshot))) throw new IllegalArgumentException("XML snapshot changed during validation/extraction");
            manifest.setProperty("invoices.sha256", extraction.invoiceHash());
            manifest.setProperty("lines.sha256", extraction.lineHash());
            manifest.setProperty("invoices", Long.toString(extraction.invoiceCount()));
            manifest.setProperty("lines", Long.toString(extraction.lineCount()));
            manifest.setProperty("document.version", extraction.documentVersion());
            status(run, manifest, "EXTRACTED");
            DatabaseStore.Imported imported;
            try (var connection = DatabaseStore.connect()) {
                imported = DatabaseStore.publish(connection, attempt, sourceHash, pinned.sha256(), extraction, mapping);
                committed = true;
            }
            manifest.setProperty("batch", imported.batch().toString());
            status(run, manifest, imported.alreadyImported() ? "ALREADY_IMPORTED" : "IMPORTED");
            return new Receipt(imported.batch(), run, imported.alreadyImported());
        } catch (Exception e) {
            manifest.setProperty("error", String.valueOf(e.getMessage()));
            try { status(run, manifest, committed ? "COMMITTED_AUDIT_ERROR" : "FAILED"); }
            catch (Exception audit) { e.addSuppressed(audit); }
            throw e;
        }
    }

    private static void status(Path run, Properties manifest, String status) throws Exception {
        manifest.setProperty("status", status);
        Path temporary = run.resolve("manifest.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) { manifest.store(output, "Ingestion attempt"); }
        Files.move(temporary, run.resolve("manifest.properties"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void main(String[] args) {
        try {
            if ((args.length == 4 || args.length == 5) && args[0].equals("generate-mapping")) {
                try (var connection = DatabaseStore.connect()) {
                    int unresolved = MappingPlan.generate(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), connection,
                        args.length == 5 ? args[4] : "ingestion");
                    System.out.println("MAPPING GENERATED: " + args[3] + "; unresolved fields=" + unresolved);
                }
            } else if (args.length == 4 && (args[0].equals("check") || args[0].equals("check-mapping"))) {
                MappingPlan mapping = MappingPlan.load(Path.of(args[3]));
                mapping.coverage(XsdFields.inspect(Path.of(args[1]), Path.of(args[2])));
                try (var connection = DatabaseStore.connect()) { mapping.database(connection); }
                if (args[0].equals("check-mapping")) {
                    System.out.println("MAPPING COMPLETE: " + mapping.identity() + "; adapter=" + mapping.adapter);
                    return;
                }
                mapping.invoiceAdapter();
                var bundle = InvoiceMapping.eligible(Path.of(args[1]), Path.of(args[2]));
                try (var connection = DatabaseStore.connect()) { DatabaseStore.eligible(connection); }
                System.out.println("ELIGIBLE: " + mapping.identity() + ", XSD bundle " + bundle.sha256());
            } else if (args.length >= 5 && args.length <= 6 && args[0].equals("ingest")) {
                var receipt = ingest(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                    Path.of(args[4]), Path.of(args.length == 6 ? args[5] : ".work/runs"));
                System.out.println((receipt.alreadyImported() ? "ALREADY_IMPORTED" : "IMPORTED")
                    + ": batch=" + receipt.batch() + ", artifacts=" + receipt.artifacts());
            } else {
                System.err.println("Usage: Ingestor generate-mapping <xsd> <schema-root> <output.yaml> [db-schema]\n"
                    + "       Ingestor check-mapping <xsd> <schema-root> <mapping.yaml>\n"
                    + "       Ingestor check <xsd> <schema-root> <mapping.yaml>\n"
                    + "       Ingestor ingest <xml> <xsd> <schema-root> <mapping.yaml> [work-directory]");
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("INGESTION REJECTED: " + e.getMessage());
            System.exit(1);
        }
    }
}
