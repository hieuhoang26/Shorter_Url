CREATE TABLE url_file_batches (
    id              UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    file_name       VARCHAR(255) NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    total_records   INT          NOT NULL DEFAULT 0,
    processed_records INT        NOT NULL DEFAULT 0,
    success_records INT          NOT NULL DEFAULT 0,
    failed_records  INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMP,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP,
    updated_by      VARCHAR(255)
);

CREATE TABLE url_file_batch_records (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    batch_id     UUID        NOT NULL REFERENCES url_file_batches(id),
    row_number   INT         NOT NULL,
    original_url TEXT        NOT NULL,
    short_code   VARCHAR(50),
    status       VARCHAR(20) NOT NULL,
    error_message TEXT,
    processed_at TIMESTAMPTZ,
    created_at   TIMESTAMP,
    created_by   VARCHAR(255),
    updated_at   TIMESTAMP,
    updated_by   VARCHAR(255)
);

CREATE INDEX idx_batch_records_batch_status ON url_file_batch_records (batch_id, status);
