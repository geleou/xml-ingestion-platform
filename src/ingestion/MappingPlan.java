import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Explicit scalar-field bindings. Loading or generating a plan never writes database data. */
public final class MappingPlan {
    public static final Path DEFAULT = Path.of("mappings/invoices-v1.yaml");
    public record Binding(String source, String xsdType, List<String> targets) {
        public Binding { targets = List.copyOf(targets); }
    }
    public record Column(String target, String type, Integer length, Integer precision, Integer scale) {}
    public final String id, adapter, databaseSchema, xsdHash, databaseHash, sha256;
    public final List<Binding> fields;
    private final byte[] document;

    private MappingPlan(byte[] bytes, Map<?, ?> yaml) throws Exception {
        keys(yaml, Set.of("format", "id", "adapter", "database_schema", "xsd_sha256", "database_sha256", "fields"));
        if (!Integer.valueOf(1).equals(yaml.get("format"))) throw new IllegalArgumentException("Mapping format must be 1");
        id = string(yaml, "id");
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid mapping id");
        adapter = string(yaml, "adapter");
        databaseSchema = string(yaml, "database_schema");
        if (!databaseSchema.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Use a simple lower-case database schema name");
        xsdHash = string(yaml, "xsd_sha256");
        databaseHash = string(yaml, "database_sha256");
        if (!xsdHash.matches("[a-f0-9]{64}") || !(databaseHash.matches("[a-f0-9]{64}") || databaseHash.equals("unbound"))) throw new IllegalArgumentException("Invalid schema fingerprint");
        List<Binding> bindings = new ArrayList<>();
        if (!(yaml.get("fields") instanceof List<?> list)) throw new IllegalArgumentException("Mapping fields must be a list");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> field)) throw new IllegalArgumentException("Each mapping field must be an object");
            keys(field, Set.of("source", "xsd_type", "targets", "suggestions"));
            String source = string(field, "source");
            String type = string(field, "xsd_type");
            List<String> targets = strings(field.get("targets"));
            if (field.containsKey("suggestions")) strings(field.get("suggestions"));
            for (String target : targets) {
                if (!target.matches("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")) {
                    throw new IllegalArgumentException("Target must be schema.table.column: " + target);
                }
                if (!target.startsWith(databaseSchema + ".")) throw new IllegalArgumentException("Target outside mapping database_schema: " + target);
            }
            bindings.add(new Binding(source, type, targets));
        }
        fields = List.copyOf(bindings);
        document = bytes.clone();
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public String identity() { return id + "@" + sha256; }
    public void snapshot(Path destination) throws Exception { Files.write(destination, document, StandardOpenOption.CREATE_NEW); }

    public static MappingPlan load(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Mapping file missing: " + path
            + ". Use xml-ingestion generate-mapping --xsd <xsd> --output <output.yaml> to create a draft.");
        if (Files.size(path) > 2_000_000) throw new IllegalArgumentException("Mapping exceeds 2 MB");
        byte[] bytes = Files.readAllBytes(path);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(2_000_000);
        Object value = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
        if (!(value instanceof Map<?, ?> yaml)) throw new IllegalArgumentException("Mapping must be a YAML object");
        return new MappingPlan(bytes, yaml);
    }

    public void coverage(XsdFields.Inventory inventory) {
        Map<String, String> remaining = new LinkedHashMap<>();
        for (var field : inventory.fields()) remaining.put(field.path(), field.type());
        Set<String> seen = new HashSet<>(), targets = new HashSet<>();
        for (Binding binding : fields) {
            if (!seen.add(binding.source())) throw new IllegalArgumentException("Duplicate XSD field mapping: " + binding.source());
            String type = remaining.remove(binding.source());
            if (type == null) throw new IllegalArgumentException("Unknown XSD field in mapping: " + binding.source());
            if (!type.equals(binding.xsdType())) throw new IllegalArgumentException("XSD type differs for " + binding.source());
            if (binding.targets().isEmpty()) throw new IllegalArgumentException("Unresolved XSD field: " + binding.source());
            for (String target : binding.targets()) if (!targets.add(target)) throw new IllegalArgumentException("Duplicate database target: " + target);
        }
        if (!remaining.isEmpty()) throw new IllegalArgumentException("Missing XSD field mappings: " + String.join(", ", remaining.keySet()));
        if (!xsdHash.equals(inventory.sha256())) throw new IllegalArgumentException("Mapping XSD bundle fingerprint differs from supplied XSDs");
    }

    public void database(Connection connection) throws Exception {
        if (databaseHash.equals("unbound")) throw new IllegalArgumentException(
            "XSD-only draft is not bound to a database. Use generate-mapping --database against an existing target, then resolve its bindings.");
        Map<String, Column> columns = new LinkedHashMap<>();
        for (Column column : columns(connection, databaseSchema)) columns.put(column.target(), column);
        for (Binding binding : fields) for (String target : binding.targets()) {
            Column column = columns.get(target);
            if (column == null) throw new IllegalArgumentException("Mapped database column does not exist: " + target);
            if (!compatible(binding.xsdType(), column)) throw new IllegalArgumentException("Unsupported/lossy type binding: " + binding.source() + " -> " + target);
        }
        if (!databaseHash.equals(DatabaseStore.contract(connection, databaseSchema))) throw new IllegalArgumentException("Mapping database fingerprint differs from target database");
    }

    /** Validate what the currently implemented row extractor/publisher can actually execute. */
    public void invoiceAdapter() {
        if (!adapter.equals(InvoiceMapping.VERSION) || !databaseSchema.equals("ingestion")) {
            throw new IllegalArgumentException("No executable adapter for " + adapter + "; draft mappings for other schemas need a row/mapping plugin");
        }
        Map<String, List<String>> expected = InvoiceMapping.bindings();
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (Binding field : fields) {
            if (actual.putIfAbsent(field.source(), field.targets()) != null) {
                throw new IllegalArgumentException("Duplicate XSD field mapping: " + field.source());
            }
        }
        if (!actual.keySet().equals(expected.keySet())) throw new IllegalArgumentException("Invoice adapter requires all of its declared XSD field bindings");
        for (String source : expected.keySet()) {
            if (!new HashSet<>(actual.get(source)).equals(new HashSet<>(expected.get(source)))) {
                throw new IllegalArgumentException("Invoice adapter cannot execute binding " + source + " -> " + actual.get(source)
                    + "; implement a new adapter for changed table/column bindings");
            }
        }
    }

    public static List<Column> columns(Connection connection, String schema) throws Exception {
        List<Column> columns = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT table_name,column_name,data_type,character_maximum_length,numeric_precision,numeric_scale FROM information_schema.columns WHERE table_schema=? ORDER BY table_name,ordinal_position")) {
            query.setString(1, schema);
            try (var rows = query.executeQuery()) {
                while (rows.next()) columns.add(new Column(schema + "." + rows.getString(1) + "." + rows.getString(2),
                    rows.getString(3), (Integer) rows.getObject(4), (Integer) rows.getObject(5), (Integer) rows.getObject(6)));
            }
        }
        return columns;
    }

    private static boolean compatible(String type, Column column) {
        String local = type.substring(type.lastIndexOf('}') + 1);
        return switch (local) {
            case "string", "token", "normalizedString", "anyURI", "ID", "IDREF", "NCName", "Name", "language", "NMTOKEN" -> column.type().equals("text");
            case "decimal", "integer", "nonNegativeInteger", "positiveInteger", "nonPositiveInteger", "negativeInteger",
                 "int", "long", "short", "byte", "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte" -> column.type().equals("numeric") && column.precision() == null && column.scale() == null;
            case "boolean" -> column.type().equals("boolean");
            default -> false;
        };
    }

    public static int generate(Path xsd, Path schemaRoot, Path output, Connection connection, String dbSchema) throws Exception {
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite mapping: " + output);
        var inventory = XsdFields.inspect(xsd, schemaRoot);
        List<Column> columns = columns(connection, dbSchema);
        if (columns.isEmpty()) throw new IllegalArgumentException("No visible columns in database schema " + dbSchema);
        boolean invoice = false;
        try { InvoiceMapping.eligible(xsd, schemaRoot); invoice = dbSchema.equals("ingestion"); }
        catch (Exception ignored) { /* Unknown XSDs receive heuristic drafts, never an invented executable adapter. */ }
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("format", 1);
        yaml.put("id", invoice ? InvoiceMapping.VERSION : "draft-" + inventory.sha256().substring(0, 12));
        yaml.put("adapter", invoice ? InvoiceMapping.VERSION : "unconfigured");
        yaml.put("database_schema", dbSchema);
        yaml.put("xsd_sha256", inventory.sha256());
        yaml.put("database_sha256", DatabaseStore.contract(connection, dbSchema));
        List<Map<String, Object>> bindings = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int unresolved = 0;
        Map<String, List<String>> approved = invoice ? InvoiceMapping.bindings() : Map.of();
        for (var field : inventory.fields()) {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("source", field.path()); binding.put("xsd_type", field.type());
            List<String> selected = new ArrayList<>(), suggestions = new ArrayList<>();
            if (approved.containsKey(field.path())) {
                for (String target : approved.get(field.path())) {
                    if (columns.stream().anyMatch(c -> c.target().equals(target) && compatible(field.type(), c))) selected.add(target);
                }
                if (selected.size() != approved.get(field.path()).size()) { suggestions.addAll(selected); selected.clear(); }
            } else {
                int best = 0;
                for (Column column : columns) {
                    if (used.contains(column.target()) || !compatible(field.type(), column)) continue;
                    int score = score(field.path(), column.target());
                    if (score > best) { best = score; suggestions.clear(); }
                    if (score > 0 && score == best) suggestions.add(column.target());
                }
                if (suggestions.size() == 1) selected.add(suggestions.get(0));
            }
            if (selected.isEmpty()) unresolved++;
            used.addAll(selected);
            binding.put("targets", selected);
            if (selected.isEmpty() && !suggestions.isEmpty()) binding.put("suggestions", suggestions);
            bindings.add(binding);
        }
        yaml.put("fields", bindings);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        String content = "# Explicit XSD -> database bindings. Empty targets must be resolved before ingestion.\n"
            + "# Generated matches are suggestions; review row relationships and meaning.\n" + new Yaml(options).dump(yaml);
        Files.writeString(output, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return unresolved;
    }

    private static int score(String source, String target) {
        String[] parts = source.replaceAll("\\{[^}]*\\}", "").replace("@", "").split("/");
        String field = normalize(parts[parts.length - 1]);
        String parent = parts.length > 1 ? normalize(parts[parts.length - 2]) : "";
        String column = normalize(target.substring(target.lastIndexOf('.') + 1));
        if (column.equals(parent + field)) return 100;
        if (column.equals(field)) return 90;
        return 0;
    }
    private static String normalize(String value) { return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT); }
    private static void keys(Map<?, ?> map, Set<String> allowed) {
        for (Object key : map.keySet()) if (!(key instanceof String) || !allowed.contains(key)) throw new IllegalArgumentException("Unknown YAML key: " + key);
    }
    private static String string(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Missing/non-string YAML value: " + key);
        return value;
    }
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a YAML list of strings");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Expected a nonempty string target");
            result.add(text);
        }
        return result;
    }
}
