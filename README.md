# XML validation and ingestion

The first working ingestion flow supports the **invoice sample XSD bundle**.
The target relational schema is predefined in [db/001-invoices.sql](db/001-invoices.sql).
It stores import batches, invoices/customers, and invoice lines, with primary
keys, foreign keys and numeric checks. The independent validator remains in
[src/validator](src/validator/README.md).

## Run locally

Requires a JDK (17+ for the current implementation), Maven, Python 3 and Docker
with Compose. From the repository root:

```sh
# Creates a random local password only if missing, then starts PostgreSQL.
bash scripts/setup-db.sh

# Generate the sample XML/XSD files if they are not present.
python3 samples/generate.py

# Downloads dependencies on the first build; tests do not need a database.
mvn -Dmaven.repo.local=.cache/m2 package

# Validate several XML files against one ruleset (this glob includes intentional failures).
./xml-ingestion validate --xsd samples/invoices/schema.xsd --schema-root samples samples/invoices/*.xml

# Initialize missing database tables, keeping compatible existing tables and data.
./xml-ingestion initialize-database --mapping mappings/invoices-v1.yaml

# Validate YAML/XSD coverage and eligibility against the live target database.
./xml-ingestion validate-mapping --xsd samples/invoices/schema.xsd --schema-root samples --mapping mappings/invoices-v1.yaml

# Generate a YAML mapping from the XSD alone; no database connection is needed.
./xml-ingestion generate-mapping --xsd samples/invoices/schema.xsd --schema-root samples --output mappings/invoices-draft.yaml

# Validate, extract, then import the valid invoice sample.
./xml-ingestion ingest --xsd samples/invoices/schema.xsd --schema-root samples --mapping mappings/invoices-v1.yaml samples/invoices/valid.xml
```

## Command line interface

`./xml-ingestion --help` lists all six commands; `<command> --help` shows its
options. The wrapper can be invoked by absolute path from another directory;
input/output paths remain relative to the caller's working directory. Build once
with the Maven command above and rebuild after source changes.

| Command | Operation | Database needed |
| --- | --- | --- |
| `validate --xsd FILE XML...` | Validate one or many XML files against an XSD | No |
| `validate-mapping --xsd FILE --mapping YAML` | Check full field coverage, database compatibility and executable adapter | Yes |
| `generate-mapping --xsd FILE [--output YAML]` | Generate bindings and a proposed target layout from XSD alone | No |
| `generate-sql --mapping YAML --output SQL` | Export initialization SQL from a supported mapping | No |
| `initialize-database --mapping YAML [--dry-run]` | Create missing supported tables and verify the resulting schema | Yes |
| `ingest --xsd FILE --mapping YAML XML...` | Validate, extract and transactionally import each document | Yes |

Commands taking an XSD accept `--schema-root DIR`; its default is the XSD's own directory.
Set it to a common trusted ancestor when imports/includes use sibling directories,
as the samples do. External network schemas and dependencies outside this root
remain blocked. `validate` and `ingest` accept positional XML paths, repeated
`--xml FILE`, or shell-expanded globs. Use `--` before positional paths beginning
with a dash. Named options also accept `--name=value`.

Exit codes are **0** for success, **1** for a rejected input or failed operation,
and **2** for CLI usage errors. Batch validation reports each file and a summary,
continues after failures, and returns 1 if any file fails. Batch ingestion checks
the shared configuration first, then processes each XML in its own transaction,
continues after per-file failures and returns 1 if any file fails. Earlier
successful files remain committed if a later file fails; the batch of CLI inputs
is not one database transaction. `ingest --work-dir DIR` selects artifact storage.

## Initialize the target tables

There is a separate **YAML mapping → SQL script** step, before applying SQL or
validating the mapping against the live database:

```sh
mkdir -p .work
./xml-ingestion generate-sql --mapping mappings/invoices-v1.yaml --output .work/invoices-initialize.sql
```

This command needs no database connection or XSD. The optional `--xsd FILE
--schema-root DIR` also verifies XSD field coverage before exporting. It refuses
to overwrite an existing output file and records the mapping identity in the SQL
header. SQL comes from the same reviewed DDL used by `initialize-database` and
includes all mapped invoice tables plus the adapter's batch metadata, primary
and foreign keys, constraints, `IF NOT EXISTS`, a transaction, and a live schema
fingerprint guard. A mismatch raises an error before commit, allowing new objects
to be rolled back while keeping existing data. The export supports the same
invoice mappings as the initializer; arbitrary unbound drafts still need a row
adapter and reviewed initialization DDL.

Review the generated file, then apply it to the local Docker database:

```sh
docker compose exec -T db psql -U ingestion -d ingestion -v ON_ERROR_STOP=1 < .work/invoices-initialize.sql
./xml-ingestion validate-mapping --xsd samples/invoices/schema.xsd --schema-root samples --mapping mappings/invoices-v1.yaml
```

Run scripts with `ON_ERROR_STOP=1` so an error stops execution. Exporting does not
apply SQL or import XML. The configured target database must already exist, and
the SQL execution account needs DDL privileges. The script uses PostgreSQL 17's
built-in SHA-256 function for the same catalog fingerprint checked by Java.

Alternatively, apply initialization directly through the CLI without executing
the exported file separately.
Given a supported mapping, initialize tables in the database selected by `DB_URL`:

```sh
# Test the required SQL and compatibility checks, then roll everything back.
./xml-ingestion initialize-database --mapping mappings/invoices-v1.yaml --dry-run

# Apply initialization; subsequent calls verify the schema and report ALREADY_INITIALIZED.
./xml-ingestion initialize-database --mapping mappings/invoices-v1.yaml
```

The target database must already exist; this command initializes its schema and
tables. Docker setup creates the local database. The mapping's adapter selects
the bundled, reviewed [invoice SQL](db/001-invoices.sql); YAML does not supply
arbitrary SQL to execute. The current initializer supports the invoice adapter
and its expected DDL fingerprint. Unbound layout drafts and other adapters need
an initialization implementation before they can be applied.

Existing tables skip CREATE statements. Missing tables are created in dependency
order, preserving existing tables and rows. The entire initialization runs in one
transaction and verifies the live database and mapping before committing. An
incompatible table, wrong mapping, SQL error or insufficient DDL permissions
rejects the operation and rolls back all objects created by that attempt.
Existing structures are not altered, replaced or truncated. Partial compatible
setups can be completed; incompatible setups require a separate migration.

`--dry-run` executes the same necessary CREATE statements and checks, then rolls
back instead of committing; `would-create` lists the tables that would be added.
It tests actual SQL execution rather than only displaying a script. The optional
`--xsd FILE --schema-root DIR` also checks complete XSD coverage before running
any initialization SQL. Without these options the adapter validates its known
binding set and database fingerprint using the mapping alone.

`validate-mapping` does not write data. It checks the live target and adapter, but
cannot guarantee every possible XML document will meet data-dependent uniqueness,
relationship or business constraints. Those are checked on the actual data during
ingestion. The independent source-file validator and older positional
`bash scripts/ingest.sh ...` entry point remain available.

PostgreSQL listens on `127.0.0.1:55432`. Database and local development user are
`ingestion`; the generated password stays in ignored `secrets/db_password`.
The container image is pinned by digest and data lives in a named Docker volume.
The initialization SQL runs only on an empty volume. Changing the SQL file does
not migrate existing databases. `docker compose stop db` stops the service while
preserving its data. This is a local development deployment; the image's initial
user owns the database and has administrative privileges.

Inspect imported data:

```sh
docker compose exec -T db psql -U ingestion -d ingestion -c 'SELECT batch_id, invoice_count, line_count, mapping_version FROM ingestion.batches;'
docker compose exec -T db psql -U ingestion -d ingestion -c 'SELECT * FROM ingestion.invoices LIMIT 5;'
docker compose exec -T db psql -U ingestion -d ingestion -c 'SELECT * FROM ingestion.invoice_lines LIMIT 5;'
```

## Processing order

1. **Mapping and eligibility, before reading XML.** Load the explicit YAML mapping
   and inventory the XSD's scalar elements and attributes, resolving local imports
   and includes. Reject missing, duplicate, unknown or unresolved field bindings.
   Check that the referenced database columns exist with supported types, and that
   the invoice adapter can execute every binding. Check fingerprints for the exact
   root XSD, included vocabulary, imported common types and target database.
   Compare the live database's columns, types, nullability, defaults, keys,
   constraints, indexes, triggers and row-security flags with its reviewed DDL
   contract. Incompatibility stops processing without database writes.
2. **Snapshot and validate.** Copy the approved schemas and source XML into a
   new attempt directory. Recheck schema hashes, record the XML checksum and
   validate the entire snapshot with the existing secure XSD validator. Invalid
   or malformed XML never reaches extraction.
3. **Extract completely.** A separate streaming SAX extractor writes
   `invoices.csv` and `invoice-lines.csv` to disk. It preserves invoice/line
   relationships and exact decimal values, normalizes XSD `xs:token` fields,
   escapes CSV values, counts records and records checksums. No database rows
   have been written yet.
4. **Import atomically.** Verify CSV checksums, acquire table locks that protect
   against concurrent DDL, and recheck eligibility. Inside one transaction,
   create the batch record, `COPY` both CSVs into temporary staging tables,
   verify row counts, insert invoices and lines into the canonical tables, then
   commit. Any constraint or import failure rolls back the batch and all rows.

Repeating the same XML bytes with the same XSD bundle and exact mapping file returns
`ALREADY_IMPORTED` and the original batch ID. Concurrent repeats are protected by
a database unique constraint. Different source bytes create a new batch; invoice
IDs are unique within each batch. Duplicate invoice IDs inside one document are
a database consistency error and roll back the entire import, even though this
sample XSD does not require unique invoice IDs.

An XSD describes structure and types, but cannot establish the semantic meaning
of arbitrary database columns. Eligibility here means **a known, reviewed mapping
and compatible database contract**, not universal automatic XSD-to-SQL mapping.
Unknown schemas, modified XSDs (even formatting changes), or changed database DDL
are conservatively rejected. New schemas require a reviewed, versioned mapping.
The initial mapping expects the `invoices/` and `common/` bundle layout under the
trusted schema root. [InvoiceMapping.java](src/ingestion/InvoiceMapping.java)
pins those artifacts; [DatabaseStore.java](src/ingestion/DatabaseStore.java)
pins the PostgreSQL 17 catalog contract for the SQL checked into this repository.

## Explicit mappings and generation

[mappings/invoices-v1.yaml](mappings/invoices-v1.yaml) lists every invoice data
field and its `schema.table.column` targets. A source uses namespace-qualified
path segments, e.g. `{urn:xml-ingestion:samples:invoices:v1}InvoiceId`; attributes
use `@{namespace}name`, with `@{}version` denoting an unqualified attribute. These
are field identifiers, not arbitrary executable XPath expressions.

The inventory includes **all scalar leaf elements and declared attributes**,
including optional fields and every choice branch. Complex container elements
describe structure; they are not scalar database fields. Each field must appear
exactly once and have at least one target. A source may populate multiple columns
(the invoice ID is also the foreign key on each line). Two sources cannot claim
the same target. There is no implicit ignore/discard operation. Generated batch
IDs, line ordinals, checksums, counts and timestamps remain the row adapter's
responsibility because they are not XSD data fields.

If a mapping is absent, ingestion fails with a generation hint. To create one
without a database:

```sh
./xml-ingestion generate-mapping --xsd samples/invoices/schema.xsd --schema-root samples --output mappings/invoices-draft.yaml
./xml-ingestion validate-mapping --xsd samples/invoices/schema.xsd --schema-root samples --mapping mappings/invoices-draft.yaml
```

`generate-mapping --xsd FILE` reads only the XSD and its local dependencies. The
default output is `<xsd-name>.mapping.yaml` beside the XSD. Unknown schemas receive
a basic proposed layout: one table per containing element path, scalar fields
as columns, and separate attribute column names. Names are normalized, bounded
to PostgreSQL identifier length and disambiguated by path hashes. The generated
`database_sha256: unbound` and `adapter: unconfigured` explicitly mark proposals
that cannot yet be ingested. No database is contacted, and no keys, relationships
or star schema are invented. The known invoice XSD instead receives the existing
invoice adapter's bindings and expected DDL fingerprint, which can be checked
against a live database with `validate-mapping`.

Add `--database` to generate against existing columns instead of proposing a
layout:

```sh
./xml-ingestion generate-mapping --database --xsd samples/invoices/schema.xsd --schema-root samples --output mappings/existing-db-draft.yaml
```

This mode reads the connected database catalog. Neither mode creates or alters
tables, and both refuse to overwrite an existing file. The default database
schema is `ingestion`; use `--db-schema NAME` to change it, and `DB_URL` to select
another database. For the known invoice adapter it reproduces the implemented
bindings. For other schemas database matching compares normalized field/parent names with column names,
filters unsupported types, and chooses only a unique best match. Ambiguous or
unmatched fields receive `targets: []` (and candidate `suggestions` where available).
Such drafts are rejected until every field is explicitly resolved. The algorithm
does not design a star schema or infer business semantics or row relationships.

The primary eligibility command is `validate-mapping`. For coverage-only checks
of a database-bound draft independently of the executable adapter, the older
positional entry point remains available:

```sh
bash scripts/ingest.sh check-mapping samples/invoices/schema.xsd samples mappings/invoices-draft.yaml
```

The older `check-mapping` reports binding completeness; `validate-mapping` and `check` additionally verify that
the importer supports the row relationships and every target binding. The current
executable adapter supports the invoice schema and its declared targets. Changing
targets or ingesting a new schema requires an adapter implementing those row and
relationship rules; a complete heuristic YAML alone does not supply that code.
Unsupported bindings are rejected rather than accepted and ignored.

The conservative inventory supports named/inline simple and complex types,
simple-type restrictions, sequences/choices/all, element references, attributes,
and explicit local imports/includes. Recursive types, wildcards, mixed content,
groups, inheritance/simple content, substitution groups and chameleon includes
are rejected with an explicit error. This ensures unsupported constructs cannot
hide omitted fields. It reads schema documents into memory, while XML data still
streams. The current column type checks support text, unconstrained numeric and
boolean targets; bounded strings/numerics and other types require further
compatibility rules. Actual values and constraints remain enforced by validation,
extraction and the final transaction.

Database-bound YAML records XSD-bundle and database fingerprints. Updating a schema requires
regenerating/reviewing its bindings. Every attempt snapshots the exact mapping;
its checksum is included in the database mapping identity (`id@sha256`) and the
local manifest. Formatting or content changes therefore create a different
mapping identity for repeat-import purposes. Existing batches from before YAML
mapping support retain their old identity and are not relabeled automatically.
The Java convenience overload uses the checked-in invoice YAML; CLI eligibility
and ingestion commands always take an explicit mapping-file argument.

## Artifacts and configuration

Every attempt that passes eligibility retains `.work/runs/<attempt-uuid>/` with
the original XML snapshot, pinned schemas, mapping YAML, completed or partial CSVs, and a
`manifest.properties` file containing status, hashes, counts and mapping version.
Failed validation/import attempts are retained for diagnosis. Keep this working
directory on trusted local storage and provision space for source copies and
CSV staging. No retention cleanup runs automatically. Use `--work-dir DIR` with
the main CLI (or the optional sixth positional argument with the older script)
to select another work directory.

The database is authoritative after commit. If the final local audit write fails,
the command can report an error even though the batch committed; retrying is safe
and resolves through its document/schema/mapping identity. Filesystem manifests
and database commits are not a distributed transaction.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:55432/ingestion` | JDBC connection URL |
| `DB_USER` | `ingestion` | Database user |
| `DB_PASSWORD_FILE` | `secrets/db_password` | Local password file |
| `DB_PASSWORD` | unset | Optional override instead of reading the password file |
| `DB_PORT` | `55432` | Docker host port; update `DB_URL` too if changed |
| `JAVA_HEAP` | `512m` | Heap used by the ingestion wrapper |

The extractor limits an individual field to 1,048,576 Java characters and numeric
lexical values to 1,000 characters. Values exceeding these operational limits
are rejected even if XSD-valid. Streaming does not guarantee constant validation
memory for XSD identity constraints. The flow is synchronous, with no job queue,
upload API, scheduling, cancellation, migration engine or production retention
policy yet. Schema compilation happens per attempt. Java 21/25 and Maven module
separation can be introduced without changing the processing order.

## Tests

`mvn -Dmaven.repo.local=.cache/m2 test` runs extraction, schema-pinning and security
checks and the existing validator regression checks. Database tests are skipped
unless explicitly enabled against a separate test database:

```sh
docker compose exec -T db createdb -U ingestion ingestion_test
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U ingestion -d ingestion_test -f /docker-entrypoint-initdb.d/001-invoices.sql
INGESTION_DB_TEST=true DB_URL=jdbc:postgresql://127.0.0.1:55432/ingestion_test mvn -Dmaven.repo.local=.cache/m2 test
```

Create and initialize the test database once. Tests also check missing/duplicate
fields, optional attributes, unsafe YAML, unsupported XSD constructs, generated
mapping completeness, ambiguous matches, nonexistent targets and narrow types.
Integration tests check committed
values, repeat imports, invalid/malformed input, incompatible DDL, tampered CSVs
and complete rollback for duplicate invoice IDs. They refuse to run against the
default demonstration database.

CLI tests cover help/usage and exit codes, multi-file validation, file paths with
spaces, XSD-only generation, output preservation and rejection of invalid XSDs.
Database tests exercise CLI mapping validation, optional catalog-based generation
and multi-file ingestion that continues after a failed document.
Initialization tests create and remove their own temporary databases to check
fresh and partial initialization, repeat calls, preserved data, incompatible
tables, failed SQL and dry-run rollback. The test database role therefore needs
permission to create test databases; the demonstration database is left intact.
SQL export tests also execute the generated script on fresh schemas, repeat it
with existing data, and verify incompatible-schema errors roll back new tables.

Implementation references: [PostgreSQL COPY](https://www.postgresql.org/docs/17/sql-copy.html),
[official PostgreSQL Docker image](https://hub.docker.com/_/postgres),
and [pgJDBC](https://jdbc.postgresql.org/).
