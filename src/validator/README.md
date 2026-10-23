# Standalone XML validator

`XmlValidator.java` validates an entire XML document against a supplied XSD 1.0
schema. It checks XML syntax, namespaces, element ordering, required content,
data types, facets and identity constraints. It uses only the JDK and streams
input through SAX. No Maven build or third-party dependencies are required.
Java 17 or newer works with the current source.

From the repository root:

```sh
java -Xmx512m src/validator/XmlValidator.java samples/invoices/valid.xml samples/invoices/schema.xsd samples
java -Xmx512m src/validator/XmlValidator.java samples/invoices/schema-invalid.xml samples/invoices/schema.xsd samples
java -Xmx512m src/validator/XmlValidator.java samples/invoices/malformed.xml samples/invoices/schema.xsd samples
```

The optional third argument is the trusted schema directory. It defaults to the
root XSD's directory. Use `samples` for these fixtures because their root XSDs
import a shared sibling directory. All imported/included schemas must resolve
to regular files inside this directory, including after resolving symbolic
links. Network schema fetches, external DTDs and document DOCTYPE declarations
are blocked. Schemas should be trusted, immutable local artifacts.

Output includes the outcome, schema path, elapsed time and up to 20 diagnostics
with locations when available. Validation stops at the diagnostic limit or a
fatal parsing error; a negative result need not list every violation.

| Outcome | Exit code | Meaning |
| --- | --- | --- |
| `VALID` | 0 | Document is well-formed and conforms to the schema |
| `INVALID_XML` | 1 | XML syntax, schema validation or XML security check failed |
| `INVALID_SCHEMA` | 2 | Schema could not be compiled or resolved under the policy |
| `ERROR` | 2 | File access or parser configuration failed |

Other Java code can call `XmlValidator.validate(xmlPath, xsdPath, schemaRoot)`
and inspect the immutable `Result` and its `valid()` method. The two-argument
overload trusts only the root XSD's directory. Each call creates its own parser
and schema validator; it does not cache schemas.

Streaming avoids building a DOM. Identity constraints can still retain many
values; the registry fixtures take substantially longer than the other samples
with the installed JDK. This initial implementation has no cancellation or
timeout API yet.

Run the dependency-free validation/security checks:

```sh
javac -d /tmp/xml-ingestion-validator-build src/validator/XmlValidator.java tests/validator/XmlValidatorTest.java
java -cp /tmp/xml-ingestion-validator-build XmlValidatorTest
```
