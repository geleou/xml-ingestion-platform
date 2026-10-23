# Synthetic XML/XSD samples

Three independent XSD 1.0 datasets for streaming validator development. All data
is synthetic. Each dataset has a root `schema.xsd`, a large included
`classifications.xsd` (12,000 enumeration values), and a relative import of
`common/types.xsd`. Always validate against the dataset's **schema.xsd**, not the
vocabulary or common schema alone. No network access is needed.

Use the standalone validator in [src/validator](../src/validator/README.md) to
validate an individual XML/XSD pair:

```sh
java -Xmx512m src/validator/XmlValidator.java samples/invoices/valid.xml samples/invoices/schema.xsd samples
```

| Dataset | Nested content | Schema-invalid case |
| --- | --- | --- |
| `invoices/` | Customers, invoice lines, quantities and prices | Negative final line price |
| `telemetry/` | Devices, timestamped measurements and units | Unknown classification in final reading |
| `registry/` | Organisations, addresses and contacts | Final entry repeats the first ID, violating `xs:key` |

Each dataset contains:

- `valid.xml`: well-formed and XSD-valid.
- `schema-invalid.xml`: well-formed, with a deliberate schema violation in the final record.
- `malformed.xml`: mismatched closing tag in the final record, violating XML syntax.

The default size is approximately **32 MiB per XML**, **288 MiB total XML**,
plus three large vocabulary XSDs and the small root/common schemas. Each record
occupies one line. Errors occur after the size target so a validator must read
almost the entire file before reporting them. `manifest.json` records exact file
sizes, SHA-256 checksums, record counts, expected outcomes and error lines.

Generate or replace the fixtures from the repository root:

```sh
python3 samples/generate.py
```

To generate approximately **1 GiB per XML** (nine files, roughly **9 GiB total**):

```sh
python3 samples/generate.py --size-mib 1024
```

The generator uses bounded memory and the Python standard library. It overwrites
the named fixture files in place; do not regenerate while validating them. Large
generated XML/XSD files and the manifest are ignored by Git; the generator and
this documentation are the reproducible source of the fixtures.

Check all nine expected outcomes with an installed JDK (Java 17 or newer):

```sh
java -Xmx512m samples/VerifySamples.java samples
```

Allow several minutes for the full check: the registry's document-wide `xs:key`
checks were substantially slower than the invoice and telemetry checks with the
installed JDK validator.

This check uses Java's built-in SAX/XSD validator and allows file-based schema
dependencies for these trusted fixtures. It is a sample check, not the planned
production secure resolver. Registry uniqueness constraints retain identity
values, so streaming input does not imply constant validation memory. These
synthetic samples exercise nesting, imports/includes, facets, enumerations,
identity constraints and late failures; they do not establish performance for
real authority schemas or cover XSD 1.1.
