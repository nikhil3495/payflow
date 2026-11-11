CREATE TABLE reconciliation_runs (
    id                 UUID        PRIMARY KEY,
    state              TEXT        NOT NULL CHECK (state IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ,
    payments_checked   BIGINT      NOT NULL DEFAULT 0,
    discrepancy_count  BIGINT      NOT NULL DEFAULT 0,
    failure_reason     TEXT
);

CREATE INDEX reconciliation_runs_started_idx ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_discrepancies (
    id             UUID        PRIMARY KEY,
    run_id         UUID        NOT NULL REFERENCES reconciliation_runs (id),
    payment_id     UUID        NOT NULL REFERENCES payments (id),
    kind           TEXT        NOT NULL CHECK (kind IN ('AGGREGATE_MISMATCH', 'LEDGER_POSITION_MISMATCH')),
    subject        TEXT        NOT NULL,
    expected_minor BIGINT      NOT NULL,
    actual_minor   BIGINT      NOT NULL,
    detected_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX reconciliation_discrepancies_run_idx ON reconciliation_discrepancies (run_id);
CREATE INDEX reconciliation_discrepancies_payment_idx ON reconciliation_discrepancies (payment_id);
