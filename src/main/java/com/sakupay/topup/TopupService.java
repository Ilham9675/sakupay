package com.sakupay.topup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakupay.account.Account;
import com.sakupay.account.AccountLocks;
import com.sakupay.account.AccountRepository;
import com.sakupay.account.AccountType;
import com.sakupay.account.VirtualAccount;
import com.sakupay.account.VirtualAccountRepository;
import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.common.error.ApiException;
import com.sakupay.ledger.LedgerService;
import com.sakupay.ledger.LedgerTransaction;
import com.sakupay.ledger.LedgerTransactionRepository;
import com.sakupay.ledger.TransactionType;
import com.sakupay.transfer.TransferService;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles the partner bank's top-up webhook: verify, de-duplicate, credit.
 *
 * <p>Banks deliver webhooks "at least once": the same event can arrive again (timeouts, retries). The bank's
 * {@code eventId} is stored with a unique constraint in the same transaction as the credit, so a repeated delivery
 * credits nothing and gets the original result back.
 */
@Service
public class TopupService {

    static final String PARTNER = "partner-bank";
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");
    private static final long MAX_AMOUNT = 1_000_000_000L;

    private final TopupProperties properties;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final VirtualAccountRepository virtualAccounts;
    private final AccountRepository accounts;
    private final AccountLocks locks;
    private final LedgerService ledger;
    private final LedgerTransactionRepository transactions;
    private final AuditService audit;
    private final TransactionTemplate inTransaction;
    private final Clock clock;

    TopupService(TopupProperties properties, ObjectMapper json, JdbcTemplate jdbc, VirtualAccountRepository virtualAccounts,
                 AccountRepository accounts, AccountLocks locks, LedgerService ledger,
                 LedgerTransactionRepository transactions, AuditService audit,
                 PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.jdbc = jdbc;
        this.virtualAccounts = virtualAccounts;
        this.accounts = accounts;
        this.locks = locks;
        this.ledger = ledger;
        this.transactions = transactions;
        this.audit = audit;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record Result(String status, String transactionReference) {
    }

    record Notification(String eventId, String virtualAccount, long amount, Instant paidAt) {
    }

    public Result receive(String timestamp, String signature, String rawBody) {
        try {
            WebhookSignature.verify(properties.webhookSecret(), timestamp, rawBody, signature, Instant.now(clock),
                    properties.timestampTolerance());
        } catch (ApiException e) {
            // Forged or replayed webhooks are worth knowing about: record them, then refuse
            inTransaction.executeWithoutResult(s ->
                    audit.record(PARTNER, AuditAction.WEBHOOK_REJECTED, "webhook:topup", e.getCode()));
            throw e;
        }
        // Only now is the body trusted enough to be parsed
        Notification n = parse(rawBody);
        return inTransaction.execute(status -> credit(n));
    }

    private Result credit(Notification n) {
        int inserted = jdbc.update("""
                INSERT INTO topup_events (event_id, va_number, amount, paid_at, received_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, n.eventId(), n.virtualAccount(), n.amount(), Timestamp.from(n.paidAt()), Timestamp.from(Instant.now(clock)));
        if (inserted == 0) {
            return duplicate(n);
        }

        VirtualAccount va = virtualAccounts.findByVaNumber(n.virtualAccount())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_VIRTUAL_ACCOUNT",
                        "No account has this virtual account number."));
        long settlementId = accounts.findIdByAccountNumberAndType(TransferService.SETTLEMENT_ACCOUNT, AccountType.INTERNAL)
                .orElseThrow();
        AccountLocks.Locked locked = locks.lock(java.util.List.of(va.getAccountId()), java.util.List.of(settlementId));
        Account customer = locked.get(va.getAccountId());
        if (!customer.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_INACTIVE", "The account cannot receive money.");
        }

        LedgerTransaction tx = ledger.post(TransactionType.TOPUP, locked.get(settlementId), customer, n.amount(),
                "Top-up via VA " + n.virtualAccount(), null);
        jdbc.update("UPDATE topup_events SET transaction_id = ? WHERE event_id = ?", tx.getId(), n.eventId());
        audit.record(PARTNER, AuditAction.TOPUP_RECEIVED, "transaction:" + tx.getReference(),
                "event=" + n.eventId() + " va=" + n.virtualAccount() + " amount=" + n.amount());
        return new Result("CREDITED", tx.getReference());
    }

    /** The same event again: answer like the first time. A different payload under the same id is refused. */
    private Result duplicate(Notification n) {
        Map<String, Object> first = jdbc.queryForMap(
                "SELECT va_number, amount, transaction_id FROM topup_events WHERE event_id = ?", n.eventId());
        if (!n.virtualAccount().equals(first.get("va_number")) || n.amount() != ((Number) first.get("amount")).longValue()) {
            throw ApiException.conflict("EVENT_ID_CONFLICT", "This eventId was already used for a different payment.");
        }
        String reference = transactions.findById(((Number) first.get("transaction_id")).longValue())
                .orElseThrow().getReference();
        return new Result("DUPLICATE", reference);
    }

    private Notification parse(String rawBody) {
        try {
            JsonNode node = json.readTree(rawBody);
            String eventId = node.path("eventId").asText("");
            String va = node.path("virtualAccount").asText("");
            long amount = node.path("amount").asLong(0);
            Instant paidAt = Instant.parse(node.path("paidAt").asText(""));
            if (!EVENT_ID.matcher(eventId).matches() || !va.startsWith(VirtualAccount.PARTNER_PREFIX)
                    || amount <= 0 || amount > MAX_AMOUNT || !node.path("amount").isIntegralNumber()) {
                throw invalid();
            }
            return new Notification(eventId, va, amount, paidAt);
        } catch (DateTimeParseException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_PAYLOAD",
                "Expected eventId, virtualAccount, a whole positive rupiah amount and paidAt (ISO-8601).");
    }
}
