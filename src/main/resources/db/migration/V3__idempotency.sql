-- Idempotency keys. A row is inserted IN_FLIGHT inside the same transaction as the work and is
-- flipped to COMPLETED before that transaction commits, so a rolled-back (5xx / crashed) request
-- leaves no row behind and its key stays retryable.

CREATE TABLE idempotency_keys (
    id                    UUID        PRIMARY KEY,
    merchant_id           TEXT        NOT NULL,
    idempotency_key       TEXT        NOT NULL CHECK (char_length(idempotency_key) BETWEEN 1 AND 255),
    endpoint              TEXT        NOT NULL,
    request_fingerprint   CHAR(64)    NOT NULL,
    state                 TEXT        NOT NULL CHECK (state IN ('IN_FLIGHT', 'COMPLETED')),
    response_status       INTEGER,
    response_content_type TEXT,
    response_body         TEXT,
    created_at            TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_keys_scope_unique UNIQUE (merchant_id, idempotency_key),
    CONSTRAINT idempotency_completed_has_response
        CHECK (state <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL))
);

CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);
