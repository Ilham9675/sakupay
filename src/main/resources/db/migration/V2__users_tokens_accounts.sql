-- Phase 1: users, refresh tokens and bank accounts.
-- Money is stored as BIGINT rupiah (no fractions), never as floating point.

CREATE TABLE users (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name            VARCHAR(100) NOT NULL,
    email                VARCHAR(254) NOT NULL UNIQUE,      -- stored lower-case
    phone                VARCHAR(20)  UNIQUE,                -- E.164 (+62...), null only for bootstrap admin
    password_hash        VARCHAR(100) NOT NULL,              -- BCrypt
    pin_hash             VARCHAR(100),                       -- BCrypt, null until the user sets a PIN
    pin_failed_attempts  INT          NOT NULL DEFAULT 0 CHECK (pin_failed_attempts >= 0),
    pin_locked_until     TIMESTAMPTZ,
    role                 VARCHAR(20)  NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN', 'AUDITOR')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0
);

-- Only a SHA-256 hash of each refresh token is stored: a database leak does not leak usable tokens.
CREATE TABLE refresh_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE accounts (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_number        VARCHAR(10)  NOT NULL UNIQUE CHECK (account_number ~ '^[0-9]{10}$'),
    user_id               BIGINT       NOT NULL REFERENCES users (id),
    currency              VARCHAR(3)   NOT NULL DEFAULT 'IDR',
    balance               BIGINT       NOT NULL DEFAULT 0 CHECK (balance >= 0),
    daily_transfer_limit  BIGINT       NOT NULL CHECK (daily_transfer_limit > 0),
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_accounts_user ON accounts (user_id);
