-- Inbound PSP webhooks (deduplicated on the PSP's event id) and outbound merchant deliveries.

CREATE TABLE webhook_events (
    id               UUID        PRIMARY KEY,
    psp_event_id     TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    payload          TEXT        NOT NULL,
    processing_state TEXT        NOT NULL CHECK (processing_state IN ('PROCESSED', 'UNMATCHED', 'IGNORED')),
    received_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT webhook_events_psp_event_unique UNIQUE (psp_event_id)
);

CREATE TABLE webhook_endpoints (
    merchant_id    TEXT        PRIMARY KEY,
    url            TEXT        NOT NULL,
    signing_secret TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE webhook_deliveries (
    id              UUID        PRIMARY KEY,
    merchant_id     TEXT        NOT NULL,
    payment_id      UUID        NOT NULL REFERENCES payments (id),
    event_type      TEXT        NOT NULL,
    -- The exact bytes that are signed and sent; never re-serialized.
    payload         TEXT        NOT NULL,
    state           TEXT        NOT NULL CHECK (state IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempt_count   INTEGER     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_status     INTEGER,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ
);

CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE state = 'PENDING';

CREATE TABLE webhook_dead_letter (
    id            UUID        PRIMARY KEY,
    delivery_id   UUID        NOT NULL UNIQUE REFERENCES webhook_deliveries (id),
    merchant_id   TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,
    payload       TEXT        NOT NULL,
    attempt_count INTEGER     NOT NULL,
    last_status   INTEGER,
    last_error    TEXT,
    dead_at       TIMESTAMPTZ NOT NULL
);
