# SakuPay

[![CI](https://github.com/Ilham9675/sakupay/actions/workflows/ci.yml/badge.svg)](https://github.com/Ilham9675/sakupay/actions/workflows/ci.yml)

A digital wallet / mini core banking backend built with Spring Boot. The focus is on what matters when a
system moves money: a double-entry ledger, transfers that are idempotent and safe under concurrency,
signed top-up webhooks, fraud rules and an audit trail that cannot be rewritten.

> **Status: Phase 0 (foundation).** The project is built in public, phase by phase. See the roadmap below.

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
| Database | PostgreSQL 16, schema versioned with **Flyway** (Hibernate only validates) |
| Tests | JUnit 5, MockMvc, **Testcontainers** (tests run against a real PostgreSQL) |
| API docs | springdoc OpenAPI, Swagger UI |
| Infrastructure | Docker (multi-stage, non-root), Docker Compose, GitHub Actions |
| Planned | Spring Security + JWT, Redis (rate limiting), dependency scanning |

## Running locally

### With Docker (app + PostgreSQL)

```bash
cp .env.example .env
docker compose up --build
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

### From the IDE or Maven

Start only the database, then run the app against it:

```bash
cp .env.example .env
docker compose up -d db
DB_PASSWORD=change-me-local-only mvn spring-boot:run
```

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/sakupay` | JDBC URL |
| `DB_USERNAME` | `sakupay` | Database user |
| `DB_PASSWORD` | (none) | Database password; never committed, set it in `.env` |
| `PORT` | `8080` | HTTP port |

## Tests

```bash
mvn verify
```

The tests start PostgreSQL 16 in a container, so Docker must be running. Without Docker they are skipped
locally; CI runs all of them and fails if any test was skipped.

## Engineering notes

- **Schema in migrations, not in entities.** `src/main/resources/db/migration` is the source of truth;
  `ddl-auto=validate` makes the app refuse to start if an entity drifts from the schema.
- **The audit log is insert-only at the database level.** A trigger rejects `UPDATE`, `DELETE` and
  `TRUNCATE` on `audit_logs`, so a bug or a compromised service account cannot rewrite history.
  `AuditLogInsertOnlyTest` proves it.
- **Errors do not leak internals.** Responses use RFC 9457 problem details, without stack traces or
  exception messages.
- **No secrets in the repository.** Credentials come from environment variables; `.env` is ignored.

## Roadmap

- [x] **Phase 0: Foundation.** Spring Boot, PostgreSQL + Flyway, Testcontainers, Docker Compose, CI
- [ ] **Phase 1: Accounts and security.** Registration, JWT access + refresh tokens, 6-digit PIN with lockout,
      roles, account opening, IDOR tests
- [ ] **Phase 2: Ledger and transfers.** Double-entry ledger, idempotency keys, pessimistic locking,
      100-request concurrency test, reconciliation
- [ ] **Phase 3: Top-up and statements.** Virtual accounts, HMAC-SHA256 signed webhooks with replay
      protection, paginated statements, CSV/PDF export
- [ ] **Phase 4: Fraud and audit.** Rule-based fraud detection, `ON_HOLD` review flow, audit endpoints
- [ ] **Phase 5: Hardening.** Rate limiting, security headers, dependency scanning, `SECURITY.md` mapped
      to the OWASP Top 10

## License

[MIT](LICENSE)
