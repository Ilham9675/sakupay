# SakuPay

[![CI](https://github.com/Ilham9675/sakupay/actions/workflows/ci.yml/badge.svg)](https://github.com/Ilham9675/sakupay/actions/workflows/ci.yml)

A digital wallet / mini core banking backend built with Spring Boot. The focus is on what matters when a
system moves money: a double-entry ledger, transfers that are idempotent and safe under concurrency,
signed top-up webhooks, fraud rules and an audit trail that cannot be rewritten.

> **Status: v1 complete (phases 0-5).** No real money is involved anywhere: balances live in a database and the
> partner bank is simulated. See [SECURITY.md](SECURITY.md) for the OWASP Top 10 mapping.

![Swagger UI](docs/swagger.jpg)

## Architecture

```mermaid
flowchart LR
    client["Client / Swagger UI"] -->|"REST + JWT"| api
    bank["Partner bank (simulated)"] -->|"HMAC-signed webhook"| api
    subgraph app["SakuPay (Spring Boot)"]
        api["Security filters: JWT, CORS, headers, rate limits"]
        api --> auth["auth: login, refresh rotation"]
        api --> user["user: PIN, roles, admin"]
        api --> account["account: accounts, virtual accounts"]
        api --> transfer["transfer: idempotency, limits"]
        api --> topup["topup: webhook verification"]
        api --> statement["statement: JSON, CSV, PDF"]
        transfer --> fraud["fraud: rules, hold, review"]
        transfer --> ledger["ledger: double entry, reconciliation"]
        topup --> ledger
        fraud --> ledger
        auth --> audit["audit: insert-only trail"]
    end
    ledger --> db[("PostgreSQL 16, schema by Flyway")]
    audit --> db
```

Packages follow features; money only ever changes in `LedgerService`, and every movement locks its accounts in one
global order (customer accounts by id, then internal accounts) so concurrent requests cannot deadlock.

## Goals

The project is designed to answer five questions with code and tests:

| Question | Answer in SakuPay |
|---|---|
| How do you make sure a transfer never happens twice? | `Idempotency-Key` header on every money movement |
| What if two transfers from the same account run at the same time? | Row locks inside one database transaction, proven with a 100-request concurrency test |
| Where does a balance come from? | A double-entry ledger; every transaction is entries that sum to zero |
| How do you stop users from reading each other's data? | Per-resource authorization, with IDOR tests |
| How do you know who did what? | An insert-only audit log, enforced by the database |

## Tech stack

| Part | Choice |
|---|---|
| Language / framework | Java 17, Spring Boot 3.5 |
| Security | Spring Security, JWT (HS256, Nimbus), BCrypt for passwords and PINs |
| Database | PostgreSQL 16, schema versioned with **Flyway** (Hibernate only validates) |
| Tests | JUnit 5, MockMvc, **Testcontainers** (tests run against a real PostgreSQL) |
| API docs | springdoc OpenAPI, Swagger UI |
| Infrastructure | Docker (multi-stage, non-root), Docker Compose, GitHub Actions |
| Hardening | Bucket4j rate limiting, security headers, Trivy image scan, Dependabot |

## API (v1)

All endpoints live under `/api/v1`. Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses
with a stable `code` field (e.g. `WRONG_PIN`, `PIN_LOCKED`, `ACCOUNT_NOT_FOUND`).

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/auth/register` | anyone | Create a user (always role `USER`) |
| POST | `/auth/login` | anyone | Access token (15 min) + refresh token (7 days) |
| POST | `/auth/refresh` | anyone with a refresh token | Rotate: new pair, the old refresh token stops working |
| POST | `/auth/logout` | anyone with a refresh token | Revoke the refresh token |
| GET | `/me` | logged in | Own profile |
| PUT | `/me/pin` | logged in | Set or change the 6-digit PIN (needs the password) |
| POST | `/me/pin/verify` | logged in | Check the PIN; counts towards the lockout |
| POST / GET | `/accounts` | logged in | Open an account (needs a PIN, max 3) / list own accounts |
| GET | `/accounts/{number}` | owner only | One account; anyone else gets `404` |
| GET | `/accounts/{number}/statement` | owner only | Statement for a period: opening/closing balance, paginated lines |
| GET | `/accounts/{number}/statement.csv` / `.pdf` | owner only | Download the statement |
| POST | `/webhooks/topups` | partner bank (HMAC signature, no JWT) | A payment into a virtual account |
| POST | `/transfers` | owner of the source account | Transfer (PIN, balance, daily limit, fraud rules); needs an `Idempotency-Key` header |
| GET | `/transfers/{reference}` | the sender | Current status, e.g. `ON_HOLD` -> `COMPLETED` / `REJECTED` |
| GET | `/admin/users` | `ADMIN` | List users |
| POST | `/admin/accounts/{number}/deposits` | `ADMIN` | Record a cash deposit at a teller (idempotent) |
| PUT | `/admin/users/{id}/role` | `ADMIN` | Change a role (not one's own) |
| POST | `/admin/users/{id}/pin-unlock` | `ADMIN` | Unlock a locked PIN |
| GET | `/fraud-alerts` | `ADMIN`, `AUDITOR` | Held transfers and their review status |
| POST | `/admin/fraud-alerts/{id}/approve` / `reject` | `ADMIN` (not the sender) | Release to the receiver / give back to the sender (reason required) |
| GET | `/audit-logs` | `AUDITOR` | Search the audit trail |
| GET | `/reconciliation` | `AUDITOR` | Prove that balances match the ledger and all money sums to zero |

Try it in Swagger UI: log in, press **Authorize** and paste the `accessToken`.

```bash
curl -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json'   -d '{"fullName":"Siti Rahma","email":"siti@example.com","phone":"081234567890","password":"Rahasia123"}'
curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json'   -d '{"email":"siti@example.com","password":"Rahasia123"}'
```

## How money moves

```
POST /transfers  (Idempotency-Key: 7f3c...)
  1. cheap checks       key format, Luhn check digit of the destination, not the same account
  2. PIN                own short transaction; wrong PINs count even though the transfer fails
  3. one DB transaction
     a. claim key       INSERT ... ON CONFLICT DO NOTHING  -> same key? return the stored result
     b. lock accounts   SELECT ... FOR UPDATE, lower id first (A->B and B->A cannot deadlock)
     c. check           active, balance >= amount, today's transfers + amount <= daily limit
     d. fraud rules     any hit? move the money to HOLD and open an alert (status ON_HOLD)
     e. post            otherwise DEBIT source, CREDIT destination, update both balances
     f. COMMIT          a deferred trigger rejects the commit if debits != credits
```

**Double-entry ledger.** Every transaction is at least two `ledger_entries` (one `DEBIT`, one `CREDIT`) of the same
amount. The `balance` column is a cache that is updated in the same transaction and can always be recomputed from
the entries. Cash deposits come from an internal `SETTLEMENT` account, so the sum of all balances in the system is
always exactly **zero**; `GET /reconciliation` checks this together with every account's balance against its entries.

**What the database enforces on its own** (proven by `LedgerIntegrityTest`, even for SQL that bypasses the app):
- a transaction whose debits and credits differ cannot be committed,
- ledger entries cannot be updated or deleted,
- a customer balance cannot go below zero.

### Concurrency results

`ConcurrentTransferTest` sends real HTTP requests to the running server (Tomcat, HikariCP with 10 connections,
PostgreSQL 16), all released at the same instant:

| Scenario | Result |
|---|---|
| 100 transfers of Rp10.000 from an account holding Rp500.000 | exactly 50 succeed, 50 get `INSUFFICIENT_FUNDS`; final balance 0, never negative; the 50 responses report 50 different balances (490.000 ... 0) |
| 50 transfers A->B and 50 B->A at the same time | all 100 succeed, no deadlock, both balances unchanged |
| The same request with the same `Idempotency-Key` sent 10 times at once | all 10 get `201` with the same reference, money moves once, 9 carry `Idempotent-Replayed: true` |

100 concurrent requests finish in about 4 seconds on a laptop; the test passed 5 runs out of 5.
After each scenario the reconciliation report must still be healthy.

## Top-ups from the partner bank

Every account gets a virtual account number (`7008` + account number). A customer pays into it at the partner
bank, and the bank calls `POST /api/v1/webhooks/topups`:

```
X-Timestamp: 1762850000
X-Signature: sha256=<hex HMAC-SHA256 of "1762850000.<raw body>" with the shared secret>

{"eventId":"evt_9f2c...","virtualAccount":"70088812345674","amount":50000,"paidAt":"2026-11-11T08:00:00Z"}
```

| Check | Why |
|---|---|
| HMAC-SHA256 over timestamp **and** raw body, compared in constant time | Nobody without the secret can create money, change the amount or re-date a request |
| Timestamp within 5 minutes | A captured genuine request cannot be replayed later |
| `eventId` unique, claimed in the same DB transaction as the credit | Banks deliver "at least once": a repeated delivery gets `DUPLICATE` and credits nothing, even 10 at the same time |
| The body is parsed only after the signature is verified | Untrusted input never reaches the JSON parser |
| Every rejected webhook is written to the audit trail | Forgery attempts are visible to auditors |

The partner bank is simulated (no real money). Try it against a running stack:

```bash
TOPUP_WEBHOOK_SECRET=local-dev-only-webhook-secret-change-me scripts/simulate-topup.sh 70088812345674 50000
```

## Statements

`GET /accounts/{number}/statement?from=2026-11-01&to=2026-11-30` returns the opening balance, every ledger line
with its running balance and counterparty, the totals and the closing balance, read in one consistent snapshot.
Days follow the `Asia/Jakarta` business day. The same statement downloads as CSV (UTF-8 with BOM for Excel) or PDF.

## Fraud rules and review

Every transfer is checked while the sender's account is locked, so the facts cannot change between counting and
deciding:

| Rule | Fires when (defaults, all configurable) | Severity |
|---|---|---|
| `LARGE_AMOUNT` | Rp5.000.000 or more in one transfer | HIGH |
| `NEW_PAYEE_LARGE` | Rp2.000.000 or more to an account this sender never paid before | MEDIUM |
| `HIGH_VELOCITY` | a 6th transfer within 10 minutes | MEDIUM |
| `NIGHT_TIME` | Rp1.000.000 or more between 00:00 and 04:00 WIB | LOW |

A transfer that hits a rule is **held, not just flagged**: the amount moves from the sender to the internal `HOLD`
account in the same transaction, so it cannot be spent twice while it waits, and the response says
`"status": "ON_HOLD"` with the `holdReasons`. An admin then decides:

```
transfer      sender  --DEBIT-->  HOLD                        status ON_HOLD,   alert OPEN
approve       HOLD    --DEBIT-->  receiver                    status COMPLETED, alert APPROVED
reject        HOLD    --DEBIT-->  sender   (reason required)  status REJECTED,  alert REJECTED
```

All legs are entries of the same transaction, so reconciliation stays healthy at every step. The alert row is
locked during a decision (two admins at once: exactly one wins), and an admin cannot review their own transfer.
Customers never see the HOLD leg: their statement shows the counterparty and the transaction's current status.

## Audit trail

Insert-only at the database level (see V1). Recorded events: registration, login success and failure, token refresh
and reuse, logout, PIN set / failure / lock / unlock, role changes, account opening, transfers (completed and held),
deposits, top-ups, rejected webhooks, and every fraud decision with its reason. Each entry has the actor, the
target, details and the client IP. `AUDITOR` reads it through `GET /audit-logs`, together with the reconciliation
report and the fraud alerts; nobody can change it.

## Security design

The full OWASP Top 10 mapping, with the known limitations, is in [SECURITY.md](SECURITY.md). Highlights:

| Threat | Control | Proven by |
|---|---|---|
| Stolen database dump | Passwords and PINs are BCrypt hashes; refresh tokens are stored only as SHA-256 hashes | `AuthenticationTest`, `PinTest` |
| Forged or tampered tokens | Only HS256 tokens signed with our key and issued by `sakupay` are accepted; `alg: none`, foreign keys, expired tokens and other issuers are rejected | `AuthenticationTest.protectedEndpointsRejectMissingForgedAndExpiredTokens` |
| Stolen refresh token | Rotation: each refresh token works once. When a used token shows up again, every session of that user is revoked | `AuthenticationTest.refreshTokensRotateAndAReusedTokenEndsEverySession` |
| User enumeration at login | Unknown e-mail and wrong password give the same response and take the same time (a dummy BCrypt check runs) | `AuthenticationTest.wrongPasswordAndUnknownEmailLookExactlyTheSame` |
| PIN guessing | 5 wrong PINs in a row lock the PIN for 30 minutes or until an admin unlocks it. The user row is locked (`SELECT ... FOR UPDATE`), so 20 parallel guesses still count one by one | `PinTest.parallelGuessesCannotGetPastTheLimit` |
| Lockout bypass via rollback | PIN attempts are committed in their own transaction (`REQUIRES_NEW`), so a failing transfer cannot roll the counter back | `PinService.verify` |
| Double spending by retries | `Idempotency-Key` claimed in the same DB transaction as the transfer; a concurrent duplicate waits on the unique index and gets the stored result | `ConcurrentTransferTest.theSameRequestSentTenTimesAtOnceMovesMoneyOnce` |
| Overdraft through a race | Both accounts are row-locked before the balance is checked; customer balances also have a `CHECK (balance >= 0)` | `ConcurrentTransferTest.hundredSimultaneousTransfersNeverOverdrawTheAccount` |
| Tampering with history | Ledger entries are insert-only and every transaction must balance, enforced by database triggers | `LedgerIntegrityTest` |
| Moving money from someone else's account | The source account is looked up together with the owner from the token | `TransferTest.invalidTransfersAreRejectedBeforeMoneyMoves` |
| Forged top-up webhook | HMAC-SHA256 signature over timestamp and body, constant-time comparison | `TopupWebhookTest.forgedTamperedAndReplayedWebhooksAreRejectedAndAudited` |
| Replayed or duplicated webhook | 5-minute timestamp window + unique `eventId` claimed with the credit | `TopupWebhookTest.tenSimultaneousDeliveriesOfOneEventCreditOnce` |
| CSV / formula injection | Statement cells starting with `= + - @` get a leading apostrophe, so a description like `=HYPERLINK(...)` typed by another user stays text in Excel | `StatementTest.theCsvOpensSafelyInASpreadsheet` |
| Statements left in caches | Downloads are sent with `Cache-Control: no-store` | `StatementTest` |
| Account takeover draining the balance | Fraud rules hold large, new-payee, rapid or night transfers; the money is reserved until a human decides | `FraudTest` |
| Insider approving their own transfer | Self-review is refused; the alert row lock lets exactly one decision win | `FraudTest.anAdminCannotApproveTheirOwnTransfer`, `twoAdminsDecidingTheSameAlertAtOnceCannotBothWin` |
| IDOR (reading someone else's account) | The owner is part of the database query; another user's account and a non-existent one give the identical `404` | `AccountTest.anotherUsersAccountLooksExactlyLikeOneThatDoesNotExist` |
| Privilege escalation | Registration ignores any `role` field; the first admin comes only from `ADMIN_EMAIL`/`ADMIN_PASSWORD`; admins cannot change their own role | `AuthenticationTest`, `RoleTest` |
| Insider abuse | Separation of duties: `ADMIN` manages users but cannot read the audit trail; `AUDITOR` reads it and nothing else | `RoleTest` |
| Password guessing, credential stuffing | Login budgets per client address and per e-mail (token buckets), checked before the password; registration and transfers are rate limited too | `RateLimitTest` |
| Browser-side attacks | CSP, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`; CORS closed unless origins are configured | `HardeningTest` |
| Vulnerable dependencies | Trivy scans the built image in CI (fails on fixable HIGH/CRITICAL library issues); Dependabot weekly; third-party actions pinned to a commit | `.github/workflows/ci.yml` |
| Weak configuration | The app refuses to start without a `JWT_SECRET` of at least 32 bytes; secrets are masked in `toString()` | `RulesTest.theAppRefusesToStartWithAWeakJwtSecret` |

## Running locally

### With Docker (app + PostgreSQL)

```bash
cp .env.example .env
docker compose up --build
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- An admin is created from `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `.env`
- Health: http://localhost:8080/actuator/health

### Without Docker or a database

```bash
set -a; . ./.env.example; set +a
mvn spring-boot:test-run
```

`TestSakuPayApplication` starts the app with a throwaway PostgreSQL 16 (embedded binaries, or Testcontainers when
Docker is running). The database is empty on every start; the admin from `.env.example` is created again.

### From the IDE or Maven

Start only the database, then run the app against it:

```bash
cp .env.example .env
docker compose up -d db
set -a; . ./.env; set +a   # loads DB_PASSWORD, JWT_SECRET, TOPUP_WEBHOOK_SECRET, ...
mvn spring-boot:run
```

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sakupay` | JDBC URL |
| `DB_USERNAME` | `sakupay` | Database user |
| `DB_PASSWORD` | (none) | Database password; never committed, set it in `.env` |
| `JWT_SECRET` | (none, required) | Signs access tokens; at least 32 bytes (`openssl rand -base64 48`) |
| `TOPUP_WEBHOOK_SECRET` | (none, required) | Shared with the partner bank to sign top-up webhooks; at least 32 bytes |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | (none) | Creates the first `ADMIN` on startup if that user does not exist |
| `CORS_ALLOWED_ORIGINS` | (none) | Browser front-ends allowed to call the API, comma separated; empty means none |
| `API_DOCS_ENABLED` | `true` | Set to `false` to hide Swagger UI and the OpenAPI document |
| `PORT` | `8080` | HTTP port |

## Tests

```bash
mvn verify
```

100 tests, all against a real PostgreSQL 16 (never H2): Testcontainers starts it when Docker is available
(always in CI), otherwise [embedded-postgres](https://github.com/zonkyio/embedded-postgres) runs the same
PostgreSQL 16 binaries as a local process. CI fails if any test was skipped.

## Engineering notes

- **Schema in migrations, not in entities.** `src/main/resources/db/migration` is the source of truth;
  `ddl-auto=validate` makes the app refuse to start if an entity drifts from the schema.
- **The audit log is insert-only at the database level.** A trigger rejects `UPDATE`, `DELETE` and
  `TRUNCATE` on `audit_logs`, so a bug or a compromised service account cannot rewrite history.
  `AuditLogInsertOnlyTest` proves it.
- **Errors do not leak internals.** Responses use RFC 9457 problem details, without stack traces or
  exception messages.
- **No secrets in the repository.** Credentials come from environment variables; `.env` is ignored.
- **Money is never a floating-point number.** Balances and limits are `BIGINT` rupiah, and the database
  rejects a negative balance with a `CHECK` constraint.
- **Idempotency without an "in progress" state.** Because the key is claimed inside the transfer's own
  transaction, a failed transfer releases its key (the client may retry it) and a duplicate arriving mid-flight
  simply waits for the first one to finish.
- **Timestamps at database precision.** The application clock ticks in microseconds, like PostgreSQL, so a replayed
  response is byte-for-byte identical to the original.
- **Account numbers have a check digit.** 10 digits (`88` + 7 random + Luhn), so a mistyped digit is caught
  before anything is looked up.

## Roadmap

- [x] **Phase 0: Foundation.** Spring Boot, PostgreSQL + Flyway, Testcontainers, Docker Compose, CI
- [x] **Phase 1: Accounts and security.** Registration, JWT access + refresh tokens with rotation, 6-digit PIN
      with lockout, roles (USER / ADMIN / AUDITOR), account opening, IDOR tests
- [x] **Phase 2: Ledger and transfers.** Double-entry ledger, idempotency keys, pessimistic locking,
      100-request concurrency test, reconciliation
- [x] **Phase 3: Top-up and statements.** Virtual accounts, HMAC-SHA256 signed webhooks with replay
      protection, paginated statements, CSV/PDF export
- [x] **Phase 4: Fraud and audit.** Rule-based fraud detection, `ON_HOLD` review flow, audit endpoints
- [x] **Phase 5: Hardening.** Rate limiting, security headers, CORS, dependency scanning, `SECURITY.md` mapped
      to the OWASP Top 10
- [ ] **Later (optional).** Explanations of fraud holds in plain Indonesian, QR payments, outbox + notifications,
      metrics (Prometheus/Grafana), a small web front-end

## License

[MIT](LICENSE)
