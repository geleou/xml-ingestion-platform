# XML Ingestion Platform — Codex Instructions

## Goal
Build an on-premises, proprietary-friendly XML validation and ingestion platform. Authorities publish XSD schemas; providers submit XML documents that must be validated and eventually mapped to an existing relational database schema.

## Confirmed constraints
- XML documents can be multiple GB in size, with complex hierarchical structures.
- Approximately 10–100 large files may be uploaded concurrently.
- Uploads occur in batches; processing is asynchronous.
- On-premises deployment, with licensing/redistribution concerns. Do not introduce MinIO.
- The XSD-to-database mapping is complex and may need manual implementation; do not assume it can be automatically generated.
- Divide and conquer: implement the standalone validator first, then the extractor, then database integration and orchestration.

## Architecture direction (provisional)
- Java 21 or 25 LTS, Maven multi-module build, JUnit 5.
- Validator: plain Java library independent of web frameworks and databases; optional CLI; JAXP/Xerces for XSD 1.0, pending confirmation of XSD version.
- Validate whole documents incrementally using SAX-based input; never construct a full DOM for GB-scale XML.
- Note: streaming input does not guarantee constant memory for XSD identity constraints; benchmark actual XSDs.
- Extractor: independent streaming parser and versioned Java mapping plugins; handle nested hierarchies and deferred references without retaining the full document graph.
- Database: PostgreSQL metadata and batch staging, COPY bulk loading, controlled publication into an existing schema.
- Raw XML/XSD: pluggable storage abstraction, initially durable shared POSIX filesystem with checksum verification, atomic upload finalization, retention, backups, and recovery.
- Orchestration: PostgreSQL-backed durable jobs with leases, idempotency, retries, and bounded worker concurrency; RabbitMQ only if needed later.
- Deployment: Linux on-premises, multiple API/coordinator instances and independently scalable worker processes; no mandatory Kubernetes.
- Pin exact XSD and mapping versions for reproducibility. Harden XML parsing against XXE, external network fetches, and resource exhaustion.

## Immediate scope: validator MVP
1. Establish XSD 1.0 versus 1.1 and XSD import/include requirements.
2. Design public validation API accepting XML path and schema reference, returning structured result and bounded diagnostics.
3. Implement secure XSD resolution using local trusted schema artifacts.
4. Implement SAX-based validation of GB-scale files, cancellation, and configurable limits.
5. Provide CLI and unit/integration tests, including invalid XML, namespace/import cases, late failures, and large synthetic inputs.
6. Benchmark memory and throughput with representative authority XSDs and documents before making performance claims.

## Working style
- Start by proposing a concise implementation plan and unresolved decisions; do not scaffold the entire platform immediately.
- Prioritize validator independence and clear interfaces.
- Ask before adding dependencies with commercial or copyleft licensing implications.
- Avoid premature microservices, Kafka, Kubernetes, or a universal declarative mapping DSL.
- Explain tradeoffs, add tests, and keep implementation incremental.
