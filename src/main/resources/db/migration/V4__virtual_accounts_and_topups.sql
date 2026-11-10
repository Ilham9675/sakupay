-- Phase 3: top-ups through virtual accounts.
--
-- A customer pays into a virtual account (VA) at the partner bank; the bank calls our webhook; we credit the
-- account with a TOPUP transaction: SETTLEMENT is debited, the customer account credited (same as a deposit).

CREATE TABLE virtual_accounts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    va_number   VARCHAR(20)  NOT NULL UNIQUE,
    account_id  BIGINT       NOT NULL UNIQUE REFERENCES accounts (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Existing customer accounts get their VA too: partner prefix 7008 + account number
INSERT INTO virtual_accounts (va_number, account_id)
SELECT '7008' || account_number, id FROM accounts WHERE type = 'CUSTOMER';

-- Top-ups are system events: no user initiates them
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check CHECK (type IN ('TRANSFER', 'DEPOSIT', 'TOPUP'));
ALTER TABLE transactions ALTER COLUMN initiated_by DROP NOT NULL;
ALTER TABLE transactions ADD CONSTRAINT transactions_initiator_required
    CHECK (type = 'TOPUP' OR initiated_by IS NOT NULL);

-- Every webhook event the bank sent, keyed by the bank's own event id. The unique event_id is what makes
-- bank retries harmless: a second delivery of the same event finds the first one and credits nothing.
CREATE TABLE topup_events (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id         VARCHAR(100) NOT NULL UNIQUE,
    va_number        VARCHAR(20)  NOT NULL,
    amount           BIGINT       NOT NULL CHECK (amount > 0),
    paid_at          TIMESTAMPTZ  NOT NULL,
    transaction_id   BIGINT       REFERENCES transactions (id),
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Statements read one account's entries by date
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at);
