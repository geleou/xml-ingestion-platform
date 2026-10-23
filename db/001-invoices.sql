-- Reviewed invoice DDL shared by Docker bootstrap and the CLI initializer.
CREATE SCHEMA ingestion;

CREATE TABLE ingestion.batches (
    batch_id uuid PRIMARY KEY,
    source_sha256 text NOT NULL,
    schema_sha256 text NOT NULL,
    mapping_version text NOT NULL,
    document_version numeric NOT NULL,
    invoice_count bigint NOT NULL,
    line_count bigint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT batches_document_key UNIQUE (source_sha256, schema_sha256, mapping_version)
);

CREATE TABLE ingestion.invoices (
    batch_id uuid NOT NULL,
    invoice_id numeric NOT NULL,
    classification text NOT NULL,
    customer_name text NOT NULL,
    country text NOT NULL,
    PRIMARY KEY (batch_id, invoice_id),
    CONSTRAINT invoices_batch_fk FOREIGN KEY (batch_id) REFERENCES ingestion.batches (batch_id),
    CONSTRAINT invoices_id_positive CHECK (invoice_id > 0)
);

CREATE TABLE ingestion.invoice_lines (
    batch_id uuid NOT NULL,
    invoice_id numeric NOT NULL,
    line_no bigint NOT NULL,
    sku text NOT NULL,
    quantity numeric NOT NULL,
    unit_price numeric NOT NULL,
    PRIMARY KEY (batch_id, invoice_id, line_no),
    CONSTRAINT lines_invoice_fk FOREIGN KEY (batch_id, invoice_id) REFERENCES ingestion.invoices (batch_id, invoice_id),
    CONSTRAINT lines_no_positive CHECK (line_no > 0),
    CONSTRAINT lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT lines_price_nonnegative CHECK (unit_price >= 0)
);
