# Security

SakuPay is a portfolio project, not a licensed financial service: it never touches real money and the partner bank is
simulated. It is built as if it were real, though, and this document maps it to the
[OWASP Top 10 (2021)](https://owasp.org/Top10/): the threat, what SakuPay does about it, and the automated test that
proves it. Every test named here runs in CI against a real PostgreSQL 16.

## Reporting a vulnerability

Please open a [private security advisory](https://github.com/Ilham9675/sakupay/security/advisories/new) instead of a
public issue.

## OWASP Top 10 mapping

### A01 Broken Access Control

| Threat | Control | Proven by |
|---|---|---|
| Reading or moving money from someone else's account (IDOR) | The owner from the token is part of every account query; another user's account and a non-existent one give the identical `404` | `AccountTest.anotherUsersAccountLooksExactlyLikeOneThatDoesNotExist`, `TransferTest.invalidTransfersAreRejectedBeforeMoneyMoves`, `StatementTest.nobodyElseCanReadOrDownloadTheStatement` |
| Following someone else's transfer | `GET /transfers/{reference}` only answers the sender | `FraudTest.aLargeTransferToANewPayeeIsHeldAndTheMoneyReserved` |
| Privilege escalation | Registration ignores any `role`; the first admin comes only from configuration; admins cannot change their own role | `AuthenticationTest.registrationNormalisesInputAndNeverReturnsSecrets`, `RoleTest` |
| Insider abuse | Separation of duties: `ADMIN` manages users and fraud reviews but cannot read the audit trail; `AUDITOR` reads and nothing else; nobody reviews their own transfer | `RoleTest`, `FraudTest.anAdminCannotApproveTheirOwnTransfer`, `FraudTest.auditorsCanReadAlertsButNotDecide` |
| Cross-site use of a stolen token from a browser | CORS is closed unless origins are configured; never `*`, never credentials | `HardeningTest.onlyConfiguredOriginsMayCallTheApiFromABrowser` |

### A02 Cryptographic Failures

| Threat | Control | Proven by |
|---|---|---|
| Database dump reveals passwords or PINs | BCrypt (cost 12 in production) for passwords and PINs; passwords over 72 bytes are refused instead of silently truncated | `AuthenticationTest`, `PinTest.settingAPinNeedsThePasswordAndAStrongPin` |
| Database dump reveals usable sessions | Refresh tokens are 256 random bits, stored only as SHA-256 hashes | `AuthenticationTest.logoutRevokesTheRefreshTokenAndStoresOnlyHashes` |
| Weak signing keys | The app refuses to start without a `JWT_SECRET` / `TOPUP_WEBHOOK_SECRET` of at least 32 bytes; secrets are masked in logs | `RulesTest.theAppRefusesToStartWithAWeakJwtSecret` |
| Statements cached by proxies or shared computers | `Cache-Control: no-store` on every response | `HardeningTest`, `StatementTest.theCsvOpensSafelyInASpreadsheet` |

### A03 Injection

| Threat | Control | Proven by |
|---|---|---|
| SQL injection | Only parameterised queries (Spring Data JPA, `JdbcTemplate` with `?` placeholders); no SQL is built from input | code review; all queries live in repositories and services |
| CSV / formula injection in exported statements | Cells starting with `= + - @` get a leading apostrophe, so a transfer description such as `=HYPERLINK(...)` stays text in Excel | `StatementExportsTest`, `StatementTest.theCsvOpensSafelyInASpreadsheet` |
| Log injection | Control characters in transfer descriptions are replaced before storage | `TransferService.clean` |

### A04 Insecure Design

| Threat | Control | Proven by |
|---|---|---|
| Double spending through retries | `Idempotency-Key` claimed in the same DB transaction as the transfer | `ConcurrentTransferTest.theSameRequestSentTenTimesAtOnceMovesMoneyOnce` |
| Overdraft through concurrent requests | Accounts row-locked in a fixed global order before balances are checked; `CHECK (balance >= 0)` in the database | `ConcurrentTransferTest.hundredSimultaneousTransfersNeverOverdrawTheAccount`, `transfersInOppositeDirectionsDoNotDeadlock` |
| Money created or lost by a bug | Double-entry ledger; a deferred trigger refuses to commit a transaction whose debits and credits differ; reconciliation proves all balances sum to zero | `LedgerIntegrityTest`, `ConcurrentTransferTest` (reconciliation after every scenario) |
| Account takeover draining the balance | Fraud rules hold large, new-payee, rapid or night-time transfers; the money is reserved until a person decides | `FraudTest`, `FraudRulesTest` |
| Two reviewers deciding at once | The alert row is locked; exactly one decision wins | `FraudTest.twoAdminsDecidingTheSameAlertAtOnceCannotBothWin` |

### A05 Security Misconfiguration

| Threat | Control | Proven by |
|---|---|---|
| Error messages leaking internals | RFC 9457 problem responses with a stable `code`; no stack traces, exception names or SQL | `ApplicationSmokeTest.anonymousRequestsGetAProblemResponseWithoutInternals` |
| Missing browser protections | CSP, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, HSTS on HTTPS | `HardeningTest.everyResponseCarriesSecurityHeaders` |
| Exposed management endpoints | Actuator exposes `health` only; Swagger UI can be switched off with `API_DOCS_ENABLED=false` | `application.yml` |
| Running as root in the container | The runtime image is a JRE-only image with a non-root user | `Dockerfile` |

### A06 Vulnerable and Outdated Components

| Threat | Control | Proven by |
|---|---|---|
| A library with a known vulnerability | CI scans the built image with Trivy and fails on fixable HIGH/CRITICAL library findings; Dependabot opens weekly update PRs for Maven, Docker and GitHub Actions | `.github/workflows/ci.yml`, `.github/dependabot.yml` |
| A framework line that no longer gets free fixes | The first Trivy scan found that Spring Boot 3.5 had left open-source support with vulnerable Tomcat, Jackson and Spring MVC versions. SakuPay moved to Spring Boot 4.1 (Spring Framework 7, Jackson 3, Hibernate 7) instead of pinning patches by hand; the scan passes with no accepted exceptions. Dependabot proposes minor and patch updates only, so the next major move is planned the same way | `pom.xml`, `.github/dependabot.yml` |
| A compromised CI action | Third-party actions are pinned to a commit SHA (the Trivy action had its tags hijacked in March 2026) | `.github/workflows/ci.yml` |

### A07 Identification and Authentication Failures

| Threat | Control | Proven by |
|---|---|---|
| Password guessing and credential stuffing | Login budgets per client address and per e-mail, checked before the password | `RateLimitTest` |
| User enumeration | Unknown e-mail and wrong password give the same answer and take the same time | `AuthenticationTest.wrongPasswordAndUnknownEmailLookExactlyTheSame` |
| Forged or expired tokens | Only HS256 tokens with our key and issuer are accepted; `alg: none`, foreign keys and expired tokens are rejected | `AuthenticationTest.protectedEndpointsRejectMissingForgedAndExpiredTokens` |
| Stolen refresh token | Rotation; reuse of a used token revokes every session of the user | `AuthenticationTest.refreshTokensRotateAndAReusedTokenEndsEverySession` |
| PIN guessing | 5 wrong PINs lock it for 30 minutes; attempts are counted under a row lock, even 20 at once | `PinTest.parallelGuessesCannotGetPastTheLimit` |
| A stolen access token alone changing the PIN | Setting the PIN needs the account password | `PinTest.settingAPinNeedsThePasswordAndAStrongPin` |

### A08 Software and Data Integrity Failures

| Threat | Control | Proven by |
|---|---|---|
| Forged top-up from someone pretending to be the bank | HMAC-SHA256 over timestamp and raw body, constant-time comparison; the body is parsed only after verification | `TopupWebhookTest.forgedTamperedAndReplayedWebhooksAreRejectedAndAudited` |
| Replayed or duplicated webhook | 5-minute timestamp window and a unique `eventId` claimed with the credit | `TopupWebhookTest.tenSimultaneousDeliveriesOfOneEventCreditOnce`, `WebhookSignatureTest` |
| Rewriting history | Ledger entries and audit logs are insert-only, enforced by database triggers | `LedgerIntegrityTest`, `AuditLogInsertOnlyTest` |

### A09 Security Logging and Monitoring Failures

| Threat | Control | Proven by |
|---|---|---|
| Attacks going unnoticed | Audit trail of logins (success and failure), token reuse, PIN failures and locks, role changes, transfers, holds, fraud decisions and rejected webhooks, with actor, target and client IP | `AuthenticationTest`, `TopupWebhookTest`, `RoleTest.anAuditorReadsTheTrailAndNothingElse` |
| Failures disappearing in a rollback | Failed logins, wrong PINs and rejected webhooks are committed even though the request fails | `AuthenticationTest.wrongPasswordAndUnknownEmailLookExactlyTheSame`, `PinTest` |
| Tampering by the people being audited | The audit trail cannot be changed (trigger) and admins cannot read it | `AuditLogInsertOnlyTest`, `RoleTest` |

### A10 Server-Side Request Forgery

SakuPay makes no outgoing HTTP requests based on user input: the partner bank calls SakuPay, never the other way
round. There is no URL a user can make the server fetch.

## Known limitations

Being honest about these is part of the design:

- **Rate limits are per instance.** The token buckets live in memory, which is right for one instance. Behind a load
  balancer each instance would count separately; Bucket4j runs the same buckets on Redis (`bucket4j-redis`) without
  changing the callers. The per-e-mail login budget also means someone can delay a victim's login for up to
  15 minutes by failing on purpose; that is the usual trade-off against password guessing.
- **Access tokens cannot be revoked before they expire.** They live 15 minutes; logout and refresh-token reuse
  end the session at the next refresh. A token deny-list would close this gap at the cost of a lookup per request.
- **One symmetric signing key (HS256).** Fine while one service issues and checks tokens; with more services an
  asymmetric key (RS256/ES256) and a JWKS endpoint would let them verify without being able to sign.
- **No second factor at login.** The transaction PIN protects money movements, but login is password only.
- **Client IP behind a proxy.** The audit trail and rate limits use the TCP peer address. Behind a reverse proxy,
  `server.forward-headers-strategy` must be enabled and the proxy trusted, or every request looks like the proxy.
- **Base image vulnerabilities are reported, not blocking.** OS packages of the JRE image change upstream; CI shows
  them but only fails on vulnerable application libraries.
