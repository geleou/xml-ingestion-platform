# Project handoff — XML validator and ingestion platform

This file summarizes architectural discussion from ChatGPT for a new Codex session.

## Business problem
An authority publishes XSD schemas; service/data providers report in XML. The system fetches or receives XML and the applicable XSD, validates compliance, and ultimately maps valid XML content into an existing common database model. The mapping from XSD/XML to database tables is a complex manual/semantic exercise, not something to automate in the first release.

## Agreed development order
1. Standalone XML/XSD validator library and CLI.
2. Standalone streaming extractor with schema-specific versioned mapping plugins.
3. Database staging and persistence adapter for existing relational schema.
4. Async batch upload, queueing, scheduling, and operational dashboard.

## Scale and operations
- GB-sized XML files with complex nesting.
- 10–100 concurrent uploads, but bounded validation concurrency.
- Asynchronous batch processing.
- On-premises deployment, potentially air-gapped.
- Avoid MinIO for licensing reasons.
- Keep original documents, metadata, audit information, and reproducible schema/mapping versions.

## Tentative stack
Java 21/25 LTS, Maven, JUnit 5, JAXP/Xerces (if XSD 1.0), PostgreSQL, shared filesystem initially, PostgreSQL job queue. These are design recommendations, not locked decisions.

## Validator requirements
- Entire XML validated against pinned authority XSD, including imported/included schemas.
- Streaming parsing, bounded diagnostics, cancellation, secure parsing, no untrusted network/entity resolution.
- Structured result with validity, elapsed time, schema identity, and errors with location when available.
- Tests and benchmarks for large, complex XML; account for XSD identity constraints that can increase memory consumption.

## Extraction design
- Distinguish extraction, semantic transformation, and persistence.
- Versioned Java mapping plugins initially; avoid universal automatic XSD-to-relational mapping.
- Handle nested structures, forward references, bounded memory, and staging.
- Canonical data should not be published until required validation and consistency checks succeed.

## Open questions
- Are XSDs 1.0 or 1.1?
- How are XSD versions and their dependencies delivered?
- What does the existing target database schema look like?
- What are the actual XML/XSD samples and validation throughput requirements?

## First Codex task
Review AGENTS.md and this brief. Propose a minimal Maven-based standalone Java XML validator library and CLI, with a secure schema resolver, structured bounded diagnostics, and tests. Discuss design first; do not build the full ingestion service.
