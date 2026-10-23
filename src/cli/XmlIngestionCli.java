import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unified user CLI; run() returns an exit code so command behavior can be tested. */
public final class XmlIngestionCli {
    private static final Set<String> COMMANDS = Set.of("validate", "validate-mapping", "generate-mapping", "generate-sql", "initialize-database", "ingest");
    private static final Map<String, Set<String>> OPTIONS = Map.of(
        "validate", Set.of("--xsd", "--schema-root", "--xml"),
        "validate-mapping", Set.of("--xsd", "--schema-root", "--mapping"),
        "generate-mapping", Set.of("--xsd", "--schema-root", "--output", "--database", "--db-schema"),
        "initialize-database", Set.of("--mapping", "--dry-run", "--xsd", "--schema-root"),
        "generate-sql", Set.of("--mapping", "--output", "--xsd", "--schema-root"),
        "ingest", Set.of("--xsd", "--schema-root", "--mapping", "--work-dir", "--xml")
    );

    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? null : args[0];
        if (args.length == 1 && Set.of("--help", "-h", "help").contains(command)) { help(null, out); return 0; }
        if (command == null) { help(null, err); return 2; }
        if (!COMMANDS.contains(command)) { err.println("USAGE ERROR: Unknown command: " + command); help(null, err); return 2; }
        if (args.length == 2 && Set.of("--help", "-h").contains(args[1])) { help(command, out); return 0; }
        try {
            Options options = parse(command, Arrays.copyOfRange(args, 1, args.length));
            if (command.equals("initialize-database") || command.equals("generate-sql")) {
                Path sqlOutput = command.equals("generate-sql") ? Path.of(options.required("--output")) : null;
                MappingPlan mapping = MappingPlan.load(Path.of(options.required("--mapping")));
                if (options.values.containsKey("--schema-root") && !options.values.containsKey("--xsd")) {
                    throw new UsageException("--schema-root requires --xsd");
                }
                if (options.values.containsKey("--xsd")) {
                    Path xsd = Path.of(options.required("--xsd"));
                    Path root = Path.of(options.values.getOrDefault("--schema-root", xsd.toAbsolutePath().getParent().toString()));
                    validSchema(xsd, root);
                    mapping.coverage(XsdFields.inspect(xsd, root));
                    InvoiceMapping.eligible(xsd, root);
                }
                DatabaseInitializer.supported(mapping);
                if (sqlOutput != null) {
                    DatabaseInitializer.writeScript(mapping, sqlOutput);
                    out.println("SQL GENERATED: " + sqlOutput + " (mapping=" + mapping.identity() + "; database not contacted)");
                    return 0;
                }
                try (var connection = DatabaseStore.connect()) {
                    var result = DatabaseInitializer.initialize(connection, mapping, options.values.containsKey("--dry-run"));
                    String status = result.dryRun() ? "INITIALIZATION CHECK PASSED (rolled back)"
                        : result.createdTables().isEmpty() ? "ALREADY_INITIALIZED" : "INITIALIZED";
                    out.printf("%s: mapping=%s %s=%s%n", status, mapping.identity(),
                        result.dryRun() ? "would-create" : "created", result.createdTables());
                }
                return 0;
            }
            Path xsd = Path.of(options.required("--xsd"));
            Path root = Path.of(options.values.getOrDefault("--schema-root", xsd.toAbsolutePath().getParent().toString()));
            switch (command) {
                case "validate" -> {
                    options.requireFiles();
                    int failures = 0;
                    for (Path xml : options.files) {
                        var result = XmlValidator.validate(xml, xsd, root);
                        out.printf("%s: %s (%d ms)%n", result.outcome(), xml, result.elapsed().toMillis());
                        diagnostics(result, err);
                        if (!result.valid()) failures++;
                    }
                    out.printf("SUMMARY: %d passed, %d failed, %d total%n", options.files.size() - failures, failures, options.files.size());
                    return failures == 0 ? 0 : 1;
                }
                case "validate-mapping" -> {
                    Path mapping = Path.of(options.required("--mapping"));
                    validSchema(xsd, root);
                    var eligibility = Ingestor.check(xsd, root, mapping);
                    out.println("MAPPING ELIGIBLE: " + eligibility.mapping().identity());
                    out.println("XSD coverage, target database and executable row bindings passed; actual data constraints are checked during ingestion.");
                    return 0;
                }
                case "generate-mapping" -> {
                    String filename = xsd.getFileName().toString().replaceFirst("(?i)\\.xsd$", "") + ".mapping.yaml";
                    Path output = Path.of(options.values.getOrDefault("--output", xsd.resolveSibling(filename).toString()));
                    String schema = options.values.getOrDefault("--db-schema", "ingestion");
                    if (!schema.matches("[a-z_][a-z0-9_]{0,62}")) throw new UsageException("--db-schema must be a simple lower-case identifier of at most 63 characters");
                    if (options.values.containsKey("--database")) {
                        validSchema(xsd, root);
                        try (var connection = DatabaseStore.connect()) {
                            int unresolved = MappingPlan.generate(xsd, root, output, connection, schema);
                            out.printf("MAPPING GENERATED: %s (database matching, %d unresolved fields)%n", output, unresolved);
                        }
                    } else {
                        var generated = XsdMappingGenerator.generate(xsd, root, output, schema);
                        out.printf("MAPPING GENERATED: %s (XSD only, %d fields, %s)%n", output, generated.fields(),
                            generated.executableInvoiceTemplate() ? "invoice template; validate against your database" : "unbound layout proposal; target database and row adapter required");
                    }
                    return 0;
                }
                case "ingest" -> {
                    Path mapping = Path.of(options.required("--mapping"));
                    Path work = Path.of(options.values.getOrDefault("--work-dir", ".work/runs"));
                    options.requireFiles();
                    // Reject a bad shared configuration before starting any document.
                    Ingestor.check(xsd, root, mapping);
                    int failures = 0;
                    for (Path xml : options.files) {
                        try {
                            var receipt = Ingestor.ingest(xml, xsd, root, mapping, work);
                            out.printf("%s: %s batch=%s artifacts=%s%n", receipt.alreadyImported() ? "ALREADY_IMPORTED" : "IMPORTED",
                                xml, receipt.batch(), receipt.artifacts());
                        } catch (Exception e) {
                            failures++;
                            err.printf("INGESTION FAILED: %s: %s%n", xml, e.getMessage());
                        }
                    }
                    out.printf("SUMMARY: %d succeeded, %d failed, %d total%n", options.files.size() - failures, failures, options.files.size());
                    return failures == 0 ? 0 : 1;
                }
                default -> throw new AssertionError(command);
            }
        } catch (UsageException e) {
            err.println("USAGE ERROR: " + e.getMessage());
            help(command, err);
            return 2;
        } catch (Exception e) {
            err.println("FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static void validSchema(Path xsd, Path root) {
        var validation = XmlValidator.validateSchema(xsd, root);
        if (!validation.valid()) throw new IllegalArgumentException("Invalid XSD ruleset: " + validation.diagnostics());
    }

    private static void diagnostics(XmlValidator.Result result, PrintStream err) {
        for (var diagnostic : result.diagnostics()) {
            err.printf("%s:%d:%d: %s%n", diagnostic.source() == null ? result.schema() : diagnostic.source(),
                diagnostic.line(), diagnostic.column(), diagnostic.message());
        }
    }

    private record Options(Map<String, String> values, List<Path> files) {
        String required(String flag) {
            String value = values.get(flag);
            if (value == null || value.isBlank()) throw new UsageException("Missing " + flag);
            return value;
        }
        void requireFiles() { if (files.isEmpty()) throw new UsageException("Provide at least one XML file (positional or --xml)"); }
    }

    private static Options parse(String command, String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        boolean positionalOnly = false;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!positionalOnly && argument.equals("--")) { positionalOnly = true; continue; }
            if (!positionalOnly && argument.startsWith("-")) {
                String[] pair = argument.split("=", 2);
                String flag = pair[0];
                if (!OPTIONS.get(command).contains(flag)) throw new UsageException("Unknown option for " + command + ": " + flag);
                if (flag.equals("--database") || flag.equals("--dry-run")) {
                    if (pair.length != 1 || values.putIfAbsent(flag, "true") != null) throw new UsageException(flag + " is a flag and may appear only once");
                    continue;
                }
                String value;
                if (pair.length == 2) value = pair[1];
                else {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new UsageException("Missing value for " + flag);
                    value = args[++i];
                }
                if (value.isBlank()) throw new UsageException("Empty value for " + flag);
                if (flag.equals("--xml")) files.add(Path.of(value));
                else if (values.putIfAbsent(flag, value) != null) throw new UsageException("Duplicate option: " + flag);
            } else {
                if (!command.equals("validate") && !command.equals("ingest")) throw new UsageException("Unexpected positional argument: " + argument);
                files.add(Path.of(argument));
            }
        }
        return new Options(values, files);
    }

    private static final class UsageException extends IllegalArgumentException {
        UsageException(String message) { super(message); }
    }

    private static void help(String command, PrintStream out) {
        out.println("Usage: xml-ingestion <command> [options]");
        if (command == null || command.equals("validate")) out.println("  validate --xsd FILE [--schema-root DIR] XML...\n    Validate one or more XMLs; --xml FILE may be repeated; shell globs are supported.");
        if (command == null || command.equals("validate-mapping")) out.println("  validate-mapping --xsd FILE --mapping YAML [--schema-root DIR]\n    Check complete XSD bindings, live database compatibility and executable adapter; no writes.");
        if (command == null || command.equals("generate-mapping")) out.println("  generate-mapping --xsd FILE [--output YAML] [--schema-root DIR] [--db-schema NAME] [--database]\n    Default: generate from XSD only. --database matches existing columns instead.\n    Default output: <xsd-name>.mapping.yaml beside the XSD; existing files are never overwritten.");
        if (command == null || command.equals("initialize-database")) out.println("  initialize-database --mapping YAML [--dry-run] [--xsd FILE --schema-root DIR]\n    Create missing supported tables; verify existing tables; never replace incompatible tables.\n    --dry-run executes initialization and compatibility checks, then rolls back. The database must already exist.");
        if (command == null || command.equals("generate-sql")) out.println("  generate-sql --mapping YAML --output SQL [--xsd FILE --schema-root DIR]\n    Export transactional initialization SQL for a supported mapping; no database connection.\n    Includes keys, constraints and compatibility checks; existing output files are never overwritten.");
        if (command == null || command.equals("ingest")) out.println("  ingest --xsd FILE --mapping YAML [--schema-root DIR] [--work-dir DIR] XML...\n    Validate, extract and import each XML in its own transaction; continue after per-file failures.");
        out.println("--schema-root defaults to the XSD's directory; all imported/included schemas must be inside it.");
        out.println("Database commands use DB_URL, DB_USER and DB_PASSWORD_FILE (or DB_PASSWORD).");
        out.println("Use --help for help, or -- before XML paths beginning with '-'. Exit: 0 success, 1 operation/input failed, 2 usage error.");
    }
}
