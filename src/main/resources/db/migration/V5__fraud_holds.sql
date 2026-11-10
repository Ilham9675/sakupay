-- Phase 4: transfers stopped by fraud rules.
--
-- A suspicious transfer is not just flagged: the money is reserved. The amount moves from the sender to the internal
-- HOLD account (so it cannot be spent twice) and waits for an admin. Approve: HOLD -> receiver. Reject: HOLD -> sender.
-- All three steps are ledger entries of the same transaction, so the books stay balanced at every moment.

ALTER TABLE transactions DROP CONSTRAINT transactions_status_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_status_check
    CHECK (status IN ('COMPLETED', 'ON_HOLD', 'REJECTED'));
ALTER TABLE transactions ADD COLUMN updated_at TIMESTAMPTZ;

INSERT INTO accounts (account_number, user_id, currency, balance, daily_transfer_limit, status, type)
VALUES ('1000000002', NULL, 'IDR', 0, 9223372036854775807, 'ACTIVE', 'INTERNAL');

CREATE TABLE fraud_alerts (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id  BIGINT       NOT NULL UNIQUE REFERENCES transactions (id),
    rules           VARCHAR(200) NOT NULL,       -- e.g. "LARGE_AMOUNT,NEW_PAYEE_LARGE"
    severity        VARCHAR(10)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    status          VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED')),
    reviewed_by     BIGINT       REFERENCES users (id),
    review_note     VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at     TIMESTAMPTZ,
    -- An alert is either waiting, or it says who decided and when
    CHECK ((status = 'OPEN') = (reviewed_by IS NULL)),
    CHECK ((status = 'OPEN') = (reviewed_at IS NULL))
);

CREATE INDEX idx_fraud_alerts_status ON fraud_alerts (status, id);

-- "Has this sender paid this receiver before?" and "how many transfers in the last minutes?"
CREATE INDEX idx_transactions_source_destination ON transactions (source_account_id, destination_account_id);
