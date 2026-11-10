-- Phase 2: double-entry ledger, transfers and idempotency keys.
--
-- Sign convention (from each account's point of view): CREDIT adds to the balance, DEBIT takes from it.
-- Every transaction is a set of entries whose credits equal its debits, so money is never created or lost:
-- the sum of all account balances, including the internal SETTLEMENT account, is always zero.

-- ---- Internal accounts ----
ALTER TABLE accounts ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER'
    CHECK (type IN ('CUSTOMER', 'INTERNAL'));
ALTER TABLE accounts ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_matches_type CHECK ((type = 'CUSTOMER') = (user_id IS NOT NULL));
-- Customers can never go below zero; the internal settlement account mirrors all money issued, so it is negative
ALTER TABLE accounts DROP CONSTRAINT accounts_balance_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_customer_balance_non_negative CHECK (type = 'INTERNAL' OR balance >= 0);

-- Counterpart of cash deposits (and, in phase 3, top-ups from partner banks)
INSERT INTO accounts (account_number, user_id, currency, balance, daily_transfer_limit, status, type)
VALUES ('1000000001', NULL, 'IDR', 0, 9223372036854775807, 'ACTIVE', 'INTERNAL');

-- ---- Transactions: one row per business event ----
CREATE TABLE transactions (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference               VARCHAR(36)  NOT NULL UNIQUE,   -- public id (UUID), safe to show to users
    type                    VARCHAR(20)  NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT')),
    status                  VARCHAR(20)  NOT NULL CHECK (status IN ('COMPLETED')),
    amount                  BIGINT       NOT NULL CHECK (amount > 0),
    source_account_id       BIGINT       NOT NULL REFERENCES accounts (id),
    destination_account_id  BIGINT       NOT NULL REFERENCES accounts (id),
    description             VARCHAR(140),
    initiated_by            BIGINT       NOT NULL REFERENCES users (id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (source_account_id <> destination_account_id)
);

-- Daily limit: sum of today's outgoing transfers of one account
CREATE INDEX idx_transactions_source_created ON transactions (source_account_id, created_at);

-- ---- Ledger entries: the source of truth for balances ----
CREATE TABLE ledger_entries (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  BIGINT       NOT NULL REFERENCES transactions (id),
    account_id      BIGINT       NOT NULL REFERENCES accounts (id),
    direction       VARCHAR(6)   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          BIGINT       NOT NULL CHECK (amount > 0),
    balance_after   BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, id);
CREATE INDEX idx_ledger_entries_transaction ON ledger_entries (transaction_id);

-- Entries are history: they can be added, never changed or removed (corrections are new, reversing entries)
CREATE FUNCTION reject_ledger_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is insert-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_entries_insert_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

-- Checked at COMMIT (deferred), after all entries of the transaction are written:
-- a transaction whose debits and credits differ cannot be committed at all.
CREATE FUNCTION check_transaction_balanced() RETURNS trigger AS $$
DECLARE
    net BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
      INTO net
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;
    IF net <> 0 THEN
        RAISE EXCEPTION 'transaction % is not balanced (credits - debits = %)', NEW.transaction_id, net;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();

-- ---- Idempotency keys ----
-- A key is claimed in the same database transaction as the money movement: if the transfer fails, the claim is
-- rolled back and the client may retry with the same key; if it succeeds, retries get the stored result.
CREATE TABLE idempotency_keys (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    transaction_id  BIGINT       REFERENCES transactions (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key)
);
