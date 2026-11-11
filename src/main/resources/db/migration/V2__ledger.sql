-- Double-entry ledger. Integrity is enforced here, not only in Java:
--   * every ledger transaction must balance (sum of debits = sum of credits) at COMMIT time
--   * entries and transactions are append-only (no UPDATE, DELETE or TRUNCATE)

CREATE TABLE ledger_accounts (
    id          UUID    PRIMARY KEY,
    type        TEXT    NOT NULL CHECK (type IN
        ('PSP_CLEARING', 'MERCHANT_RECEIVABLE', 'MERCHANT_PAYABLE', 'FEE_REVENUE', 'REFUND_LIABILITY')),
    -- '*' marks a platform-wide account; otherwise the owning merchant.
    merchant_id TEXT    NOT NULL DEFAULT '*',
    currency    CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    UNIQUE (type, merchant_id, currency)
);

CREATE TABLE ledger_transactions (
    id           UUID        PRIMARY KEY,
    payment_id   UUID        NOT NULL REFERENCES payments (id),
    operation_id UUID        NOT NULL UNIQUE REFERENCES payment_operations (id),
    description  TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ledger_transactions_payment_idx ON ledger_transactions (payment_id);

CREATE TABLE ledger_entries (
    id             BIGSERIAL   PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL REFERENCES ledger_accounts (id),
    direction      TEXT        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);

CREATE FUNCTION payflow_assert_ledger_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    tx_id   UUID;
    debits  NUMERIC;
    credits NUMERIC;
    n       BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        tx_id := NEW.transaction_id;
    ELSE
        tx_id := NEW.id;
    END IF;

    -- NUMERIC sums: summing BIGINT amounts can never overflow here.
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO debits, credits, n
      FROM ledger_entries
     WHERE transaction_id = tx_id;

    IF n < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'ledger transaction % is not balanced: debits=% credits=% entries=%',
            tx_id, debits, credits, n
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

-- Deferred: a transaction's entries are inserted one by one and only have to balance at COMMIT.
CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payflow_assert_ledger_balanced();

-- Also fires for a transaction header that never received any entries.
CREATE CONSTRAINT TRIGGER ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payflow_assert_ledger_balanced();

CREATE FUNCTION payflow_ledger_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION payflow_ledger_append_only();
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION payflow_ledger_append_only();
CREATE TRIGGER ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION payflow_ledger_append_only();
CREATE TRIGGER ledger_transactions_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION payflow_ledger_append_only();
