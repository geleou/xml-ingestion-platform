import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** XSD-only mapping proposals. This class never opens a database connection. */
public final class XsdMappingGenerator {
    public record Generated(int fields, boolean executableInvoiceTemplate) {}

    public static Generated generate(Path xsd, Path schemaRoot, Path output, String dbSchema) throws Exception {
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite mapping: " + output);
        if (!dbSchema.matches("[a-z_][a-z0-9_]{0,62}")) throw new IllegalArgumentException("Invalid target database schema name");
        var validation = XmlValidator.validateSchema(xsd, schemaRoot);
        if (!validation.valid()) throw new IllegalArgumentException("Invalid XSD ruleset: " + validation.diagnostics());
        var inventory = XsdFields.inspect(xsd, schemaRoot);
        boolean invoice = false;
        try { InvoiceMapping.eligible(xsd, schemaRoot); invoice = dbSchema.equals("ingestion"); }
        catch (Exception ignored) { /* Propose a layout for schemas without a known executable adapter. */ }
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("format", 1);
        yaml.put("id", invoice ? InvoiceMapping.VERSION : "draft-" + inventory.sha256().substring(0, 12));
        yaml.put("adapter", invoice ? InvoiceMapping.VERSION : "unconfigured");
        yaml.put("database_schema", dbSchema);
        yaml.put("xsd_sha256", inventory.sha256());
        yaml.put("database_sha256", invoice ? DatabaseStore.reviewedInvoiceContract() : "unbound");
        Map<String, List<String>> approved = invoice ? InvoiceMapping.bindings() : Map.of();
        Map<String, String> tables = new HashMap<>();
        Map<String, Set<String>> usedColumns = new HashMap<>();
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (var field : inventory.fields()) {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("source", field.path());
            binding.put("xsd_type", field.type());
            List<String> targets;
            if (invoice) targets = new ArrayList<>(approved.get(field.path()));
            else {
                List<String> parts = segments(field.path());
                String parent = String.join("/", parts.subList(0, parts.size() - 1));
                String table = tables.get(parent);
                if (table == null) {
                    String label = parts.size() == 1 ? "document" : String.join("_", parts.subList(0, parts.size() - 1).stream().map(XsdMappingGenerator::local).toList());
                    table = identifier(label, 49) + "_" + suffix(parent);
                    tables.put(parent, table);
                }
                String last = parts.get(parts.size() - 1);
                String name = identifier((last.startsWith("@") ? "attr_" : "") + local(last), 63);
                Set<String> used = usedColumns.computeIfAbsent(table, key -> new HashSet<>());
                if (!used.add(name)) {
                    name = identifier(name, 49) + "_" + suffix(field.path());
                    if (!used.add(name)) throw new IllegalArgumentException("Cannot generate unique column for " + field.path());
                }
                targets = List.of(dbSchema + "." + table + "." + name);
            }
            binding.put("targets", targets);
            bindings.add(binding);
        }
        yaml.put("fields", bindings);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String comment = "# Generated from XSD only; no database was contacted or modified.\n"
            + (invoice ? "# Targets use the implemented invoice adapter and its predefined DDL contract.\n"
                : "# Targets propose one table per containing element path. No keys/relationships are inferred.\n"
                    + "# This unbound draft needs a target database and row adapter before ingestion.\n");
        Files.writeString(output, comment + new Yaml(options).dump(yaml), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return new Generated(bindings.size(), invoice);
    }

    /** Split Clark-notation paths without splitting slashes inside namespace URIs. */
    private static List<String> segments(String source) {
        List<String> result = new ArrayList<>();
        boolean namespace = false;
        int start = 1;
        for (int i = 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') namespace = true;
            if (c == '}') namespace = false;
            if (c == '/' && !namespace) { result.add(source.substring(start, i)); start = i + 1; }
        }
        result.add(source.substring(start));
        return result;
    }
    private static String local(String part) { return part.substring(part.lastIndexOf('}') + 1); }
    private static String identifier(String name, int limit) {
        String result = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        if (result.isEmpty()) result = "field";
        if (Character.isDigit(result.charAt(0))) result = "f_" + result;
        return result.substring(0, Math.min(limit, result.length()));
    }
    private static String suffix(String path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }
}
