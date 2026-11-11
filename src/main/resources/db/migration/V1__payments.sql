-- Payments and their operations. Money is always BIGINT minor units.

CREATE TABLE payments (
    id               UUID        PRIMARY KEY,
    merchant_id      TEXT        NOT NULL CHECK (merchant_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency         CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    authorized_minor BIGINT      NOT NULL CHECK (authorized_minor > 0),
    captured_minor   BIGINT      NOT NULL DEFAULT 0 CHECK (captured_minor >= 0),
    refunded_minor   BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
    voided_minor     BIGINT      NOT NULL DEFAULT 0 CHECK (voided_minor >= 0),
    state            TEXT        NOT NULL CHECK (state IN
        ('AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED')),
    psp_reference    TEXT        NOT NULL,
    reference        TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    -- Defence in depth: the application enforces these, the database refuses to store a violation.
    -- Sums are widened to NUMERIC so the check itself cannot overflow BIGINT.
    CONSTRAINT payments_captured_plus_voided_bounded
        CHECK (captured_minor::NUMERIC + voided_minor::NUMERIC <= authorized_minor::NUMERIC),
    CONSTRAINT payments_refunded_bounded CHECK (refunded_minor <= captured_minor)
);

CREATE INDEX payments_merchant_created_idx ON payments (merchant_id, created_at DESC);

CREATE TABLE payment_operations (
    id            UUID        PRIMARY KEY,
    payment_id    UUID        NOT NULL REFERENCES payments (id),
    type          TEXT        NOT NULL CHECK (type IN ('AUTHORIZE', 'CAPTURE', 'REFUND', 'VOID')),
    amount_minor  BIGINT      NOT NULL CHECK (amount_minor > 0),
    fee_minor     BIGINT      NOT NULL DEFAULT 0 CHECK (fee_minor >= 0 AND fee_minor <= amount_minor),
    state         TEXT        NOT NULL CHECK (state IN ('SUCCEEDED', 'FAILED')),
    psp_reference TEXT        NOT NULL,
    psp_status    TEXT        NOT NULL DEFAULT 'PENDING' CHECK (psp_status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX payment_operations_payment_idx ON payment_operations (payment_id, created_at);
CREATE INDEX payment_operations_psp_reference_idx ON payment_operations (psp_reference);
