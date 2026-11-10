# SakuPay

[![CI](https://github.com/Ilham9675/sakupay/actions/workflows/ci.yml/badge.svg)](https://github.com/Ilham9675/sakupay/actions/workflows/ci.yml)

A digital wallet / mini core banking backend built with Spring Boot. The focus is on what matters when a
system moves money: a double-entry ledger, transfers that are idempotent and safe under concurrency,
signed top-up webhooks, fraud rules and an audit trail that cannot be rewritten.

> **Status: Phase 1 (accounts and security) done.** The project is built in public, phase by phase. See the roadmap below.

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
| Planned | Redis (rate limiting), dependency scanning |

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
| GET | `/admin/users` | `ADMIN` | List users |
| PUT | `/admin/users/{id}/role` | `ADMIN` | Change a role (not one's own) |
| POST | `/admin/users/{id}/pin-unlock` | `ADMIN` | Unlock a locked PIN |
| GET | `/audit-logs` | `AUDITOR` | Search the audit trail |

Try it in Swagger UI: log in, press **Authorize** and paste the `accessToken`.

```bash
curl -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json'   -d '{"fullName":"Siti Rahma","email":"siti@example.com","phone":"081234567890","password":"Rahasia123"}'
curl -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json'   -d '{"email":"siti@example.com","password":"Rahasia123"}'
```

## Security design

| Threat | Control | Proven by |
|---|---|---|
| Stolen database dump | Passwords and PINs are BCrypt hashes; refresh tokens are stored only as SHA-256 hashes | `AuthenticationTest`, `PinTest` |
| Forged or tampered tokens | Only HS256 tokens signed with our key and issued by `sakupay` are accepted; `alg: none`, foreign keys, expired tokens and other issuers are rejected | `AuthenticationTest.protectedEndpointsRejectMissingForgedAndExpiredTokens` |
| Stolen refresh token | Rotation: each refresh token works once. When a used token shows up again, every session of that user is revoked | `AuthenticationTest.refreshTokensRotateAndAReusedTokenEndsEverySession` |
| User enumeration at login | Unknown e-mail and wrong password give the same response and take the same time (a dummy BCrypt check runs) | `AuthenticationTest.wrongPasswordAndUnknownEmailLookExactlyTheSame` |
| PIN guessing | 5 wrong PINs in a row lock the PIN for 30 minutes or until an admin unlocks it. The user row is locked (`SELECT ... FOR UPDATE`), so 20 parallel guesses still count one by one | `PinTest.parallelGuessesCannotGetPastTheLimit` |
| Lockout bypass via rollback | PIN attempts are committed in their own transaction (`REQUIRES_NEW`), so a failing transfer cannot roll the counter back | `PinService.verify` |
| IDOR (reading someone else's account) | The owner is part of the database query; another user's account and a non-existent one give the identical `404` | `AccountTest.anotherUsersAccountLooksExactlyLikeOneThatDoesNotExist` |
| Privilege escalation | Registration ignores any `role` field; the first admin comes only from `ADMIN_EMAIL`/`ADMIN_PASSWORD`; admins cannot change their own role | `AuthenticationTest`, `RoleTest` |
| Insider abuse | Separation of duties: `ADMIN` manages users but cannot read the audit trail; `AUDITOR` reads it and nothing else | `RoleTest` |
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

### From the IDE or Maven

Start only the database, then run the app against it:

```bash
cp .env.example .env
docker compose up -d db
DB_PASSWORD=change-me-local-only JWT_SECRET=local-dev-only-replace-with-openssl-rand-base64-48 mvn spring-boot:run
```

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sakupay` | JDBC URL |
| `DB_USERNAME` | `sakupay` | Database user |
| `DB_PASSWORD` | (none) | Database password; never committed, set it in `.env` |
| `JWT_SECRET` | (none, required) | Signs access tokens; at least 32 bytes (`openssl rand -base64 48`) |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | (none) | Creates the first `ADMIN` on startup if that user does not exist |
| `PORT` | `8080` | HTTP port |

## Tests

```bash
mvn verify
```

47 tests, all against a real PostgreSQL 16 (never H2): Testcontainers starts it when Docker is available
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
- **Account numbers have a check digit.** 10 digits (`88` + 7 random + Luhn), so a mistyped digit is caught
  before anything is looked up.

## Roadmap

- [x] **Phase 0: Foundation.** Spring Boot, PostgreSQL + Flyway, Testcontainers, Docker Compose, CI
- [x] **Phase 1: Accounts and security.** Registration, JWT access + refresh tokens with rotation, 6-digit PIN
      with lockout, roles (USER / ADMIN / AUDITOR), account opening, IDOR tests
- [ ] **Phase 2: Ledger and transfers.** Double-entry ledger, idempotency keys, pessimistic locking,
      100-request concurrency test, reconciliation
- [ ] **Phase 3: Top-up and statements.** Virtual accounts, HMAC-SHA256 signed webhooks with replay
      protection, paginated statements, CSV/PDF export
- [ ] **Phase 4: Fraud and audit.** Rule-based fraud detection, `ON_HOLD` review flow, audit endpoints
- [ ] **Phase 5: Hardening.** Rate limiting, security headers, dependency scanning, `SECURITY.md` mapped
      to the OWASP Top 10

## License

[MIT](LICENSE)
