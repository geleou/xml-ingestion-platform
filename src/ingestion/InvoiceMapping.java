import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Reviewed invoice mapping v1. Unknown/modified schemas require a new reviewed mapping. */
public final class InvoiceMapping {
    public static final String VERSION = "invoices-v1";
    public static final String NAMESPACE = "urn:xml-ingestion:samples:invoices:v1";
    private record Artifact(String path, String hash) {}
    private static final List<Artifact> APPROVED = List.of(
        new Artifact("invoices/schema.xsd", "1052ba3c5e788c720324a03168a5beac7634ecd1b3e61891e583e86c031742e4"),
        new Artifact("invoices/classifications.xsd", "5e35523342e368b0e2246937f17604ece907e4c8f5ea985eb5b6e7e369ff658f"),
        new Artifact("common/types.xsd", "78033d3cf985008b90b5f9500d65749b36d8435423beaad90fb8c9129105870d")
    );
    public record Bundle(Path root, Path xsd, String sha256) {}

    /** The row adapter's executable bindings, including inherited invoice IDs on lines. */
    public static Map<String, List<String>> bindings() {
        String root = "/{" + NAMESPACE + "}Invoices";
        String invoice = root + "/{" + NAMESPACE + "}Invoice";
        String customer = invoice + "/{" + NAMESPACE + "}Customer";
        String line = invoice + "/{" + NAMESPACE + "}Lines/{" + NAMESPACE + "}Line";
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(root + "/@{}version", List.of("ingestion.batches.document_version"));
        fields.put(invoice + "/{" + NAMESPACE + "}InvoiceId", List.of("ingestion.invoices.invoice_id", "ingestion.invoice_lines.invoice_id"));
        fields.put(invoice + "/{" + NAMESPACE + "}Classification", List.of("ingestion.invoices.classification"));
        fields.put(customer + "/{" + NAMESPACE + "}Name", List.of("ingestion.invoices.customer_name"));
        fields.put(customer + "/{" + NAMESPACE + "}Country", List.of("ingestion.invoices.country"));
        fields.put(line + "/{" + NAMESPACE + "}Sku", List.of("ingestion.invoice_lines.sku"));
        fields.put(line + "/{" + NAMESPACE + "}Quantity", List.of("ingestion.invoice_lines.quantity"));
        fields.put(line + "/{" + NAMESPACE + "}UnitPrice", List.of("ingestion.invoice_lines.unit_price"));
        return fields;
    }

    public static Bundle eligible(Path xsd, Path trustedRoot) throws IOException {
        Path root = trustedRoot.toRealPath();
        Path entry = xsd.toRealPath();
        if (!entry.equals(root.resolve("invoices/schema.xsd").toRealPath())) {
            throw new IllegalArgumentException("No reviewed invoice mapping for this root XSD");
        }
        MessageDigest bundle = digest();
        for (Artifact artifact : APPROVED) {
            Path path = root.resolve(artifact.path()).toRealPath();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Schema dependency escapes the trusted root: " + artifact.path());
            }
            String actual = sha256(path);
            if (!artifact.hash().equals(actual)) {
                throw new IllegalArgumentException("XSD is not eligible for mapping " + VERSION + ": " + artifact.path()
                    + " differs from the reviewed schema version");
            }
            bundle.update((artifact.path() + "\n" + actual + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Bundle(root, entry, HexFormat.of().formatHex(bundle.digest()));
    }

    public static Bundle snapshot(Bundle source, Path destination) throws IOException {
        for (Artifact artifact : APPROVED) {
            Path target = destination.resolve(artifact.path());
            Files.createDirectories(target.getParent());
            Files.copy(source.root().resolve(artifact.path()), target);
        }
        // Rechecking the copy detects schema changes between eligibility and snapshotting.
        return eligible(destination.resolve("invoices/schema.xsd"), destination);
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
