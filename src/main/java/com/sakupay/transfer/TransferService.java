package com.sakupay.transfer;

import com.sakupay.account.Account;
import com.sakupay.account.AccountLocks;
import com.sakupay.account.AccountNumbers;
import com.sakupay.account.AccountRepository;
import com.sakupay.account.AccountType;
import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.common.error.ApiException;
import com.sakupay.fraud.FraudProperties;
import com.sakupay.fraud.FraudReviewService;
import com.sakupay.fraud.FraudRule;
import com.sakupay.fraud.FraudRules;
import com.sakupay.ledger.LedgerEntryRepository;
import com.sakupay.ledger.LedgerService;
import com.sakupay.ledger.LedgerTransaction;
import com.sakupay.ledger.LedgerTransactionRepository;
import com.sakupay.ledger.TransactionStatus;
import com.sakupay.ledger.TransactionType;
import com.sakupay.transfer.TransferDtos.TransactionResponse;
import com.sakupay.transfer.TransferDtos.TransferRequest;
import com.sakupay.user.PinService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Moves money between accounts.
 *
 * <p>Order of a transfer:
 * <ol>
 *   <li>Cheap checks without the database (key format, account number check digit, same account).</li>
 *   <li>PIN check in its own short transaction, so a wrong PIN is counted even though the transfer fails,
 *       and no request ever holds two database connections at once.</li>
 *   <li>One database transaction: claim the idempotency key, lock both accounts in ascending id order
 *       (two opposite transfers can never deadlock), check status, balance and daily limit, run the fraud rules,
 *       then either post to the ledger or move the money to HOLD and open a fraud alert.</li>
 * </ol>
 */
@Service
public class TransferService {

    public static final String SETTLEMENT_ACCOUNT = "1000000001";

    private final AccountRepository accounts;
    private final AccountLocks locks;
    private final LedgerService ledger;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final IdempotencyStore idempotency;
    private final PinService pinService;
    private final FraudRules fraudRules;
    private final FraudProperties fraudProperties;
    private final FraudReviewService fraudReviews;
    private final AuditService audit;
    private final TransactionTemplate inTransaction;
    private final Clock clock;
    private final ZoneId businessZone;

    TransferService(AccountRepository accounts, AccountLocks locks, LedgerService ledger, LedgerTransactionRepository transactions,
                    LedgerEntryRepository entries, IdempotencyStore idempotency, PinService pinService,
                    FraudRules fraudRules, FraudProperties fraudProperties, FraudReviewService fraudReviews,
                    AuditService audit, PlatformTransactionManager transactionManager, Clock clock,
                    @Value("${sakupay.business-zone}") ZoneId businessZone) {
        this.accounts = accounts;
        this.locks = locks;
        this.ledger = ledger;
        this.transactions = transactions;
        this.entries = entries;
        this.idempotency = idempotency;
        this.pinService = pinService;
        this.fraudRules = fraudRules;
        this.fraudProperties = fraudProperties;
        this.fraudReviews = fraudReviews;
        this.audit = audit;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.businessZone = businessZone;
    }

    /** The result, and whether it was replayed from an earlier request with the same key. */
    public record Outcome(TransactionResponse transaction, boolean replayed) {
    }

    public Outcome transfer(long userId, String idempotencyKey, TransferRequest request) {
        IdempotencyStore.validate(idempotencyKey);
        String description = clean(request.description());
        if (!AccountNumbers.isValid(request.destinationAccount())) {
            throw ApiException.badRequest("INVALID_ACCOUNT_NUMBER",
                    "The destination account number is not valid. Please check it for typos.");
        }
        if (request.sourceAccount().equals(request.destinationAccount())) {
            throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination must be different accounts.");
        }
        String hash = hash("TRANSFER", request.sourceAccount(), request.destinationAccount(),
                request.amount().toString(), description);

        pinService.verify(userId, request.pin());

        return inTransaction.execute(status -> {
            Optional<Long> earlier = idempotency.claim(userId, idempotencyKey, hash);
            if (earlier.isPresent()) {
                return replay(earlier.get(), true);
            }
            long sourceId = accounts.findIdByAccountNumberAndOwnerId(request.sourceAccount(), userId)
                    .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
            long destinationId = accounts.findIdByAccountNumberAndType(request.destinationAccount(), AccountType.CUSTOMER)
                    .orElseThrow(() -> ApiException.notFound("DESTINATION_NOT_FOUND", "The destination account does not exist."));
            AccountLocks.Locked locked = locks.lock(List.of(sourceId, destinationId), List.of());
            Account source = locked.get(sourceId);
            Account destination = locked.get(destinationId);

            if (!source.isActive()) {
                throw ApiException.conflict("ACCOUNT_INACTIVE", "Your account is " + source.getStatus() + ".");
            }
            if (!destination.isActive()) {
                throw ApiException.conflict("DESTINATION_INACTIVE", "The destination account cannot receive money.");
            }
            long amount = request.amount();
            if (source.getBalance() < amount) {
                throw ApiException.conflict("INSUFFICIENT_FUNDS", "The balance is not enough for this transfer.")
                        .with("balance", source.getBalance());
            }
            long usedToday = transactions.sumOutgoingTransfersSince(source.getId(), startOfBusinessDay());
            if (usedToday + amount > source.getDailyTransferLimit()) {
                throw ApiException.conflict("DAILY_LIMIT_EXCEEDED", "This transfer would exceed today's transfer limit.")
                        .with("dailyLimit", source.getDailyTransferLimit())
                        .with("remainingToday", Math.max(0, source.getDailyTransferLimit() - usedToday));
            }

            // Fraud rules see consistent facts: the sender's account is locked, so no other transfer from it can
            // slip in between counting and deciding
            Instant now = Instant.now(clock);
            List<FraudRule> hits = fraudRules.evaluate(amount, now,
                    transactions.countTransfersSince(source.getId(), now.minus(fraudProperties.velocityWindow())),
                    transactions.existsBySourceAccountIdAndDestinationAccountIdAndTypeAndStatus(source.getId(),
                            destination.getId(), TransactionType.TRANSFER, TransactionStatus.COMPLETED));
            String route = source.getAccountNumber() + "->" + destination.getAccountNumber() + " amount=" + amount;
            LedgerTransaction tx;
            if (hits.isEmpty()) {
                tx = ledger.post(TransactionType.TRANSFER, source, destination, amount, description, userId);
                audit.record(AuditService.user(userId), AuditAction.TRANSFER_COMPLETED, "transaction:" + tx.getReference(), route);
            } else {
                // Internal accounts are always locked after customer accounts (see AccountLocks)
                Account hold = locks.lockInternal(fraudReviews.holdAccountId());
                tx = ledger.hold(source, hold, destination, amount, description, userId);
                fraudReviews.open(tx.getId(), hits);
                audit.record(AuditService.user(userId), AuditAction.TRANSFER_HELD, "transaction:" + tx.getReference(),
                        route + " rules=" + hits);
            }
            idempotency.complete(userId, idempotencyKey, tx.getId());
            return new Outcome(toResponse(tx, source.getBalance()), false);
        });
    }

    /** A cash deposit at a teller, recorded by an admin: SETTLEMENT is debited, the customer account credited. */
    public Outcome deposit(long adminId, String idempotencyKey, String accountNumber, long amount, String rawDescription) {
        IdempotencyStore.validate(idempotencyKey);
        String description = clean(rawDescription);
        String hash = hash("DEPOSIT", accountNumber, Long.toString(amount), description);

        return inTransaction.execute(status -> {
            Optional<Long> earlier = idempotency.claim(adminId, idempotencyKey, hash);
            if (earlier.isPresent()) {
                return replay(earlier.get(), true);
            }
            long customerId = accounts.findIdByAccountNumberAndType(accountNumber, AccountType.CUSTOMER)
                    .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
            long settlementId = accounts.findIdByAccountNumberAndType(SETTLEMENT_ACCOUNT, AccountType.INTERNAL)
                    .orElseThrow();
            AccountLocks.Locked locked = locks.lock(List.of(customerId), List.of(settlementId));
            Account customer = locked.get(customerId);
            if (!customer.isActive()) {
                throw ApiException.conflict("ACCOUNT_INACTIVE", "The account cannot receive money.");
            }

            LedgerTransaction tx = ledger.post(TransactionType.DEPOSIT, locked.get(settlementId), customer, amount,
                    description, adminId);
            idempotency.complete(adminId, idempotencyKey, tx.getId());
            audit.record(AuditService.user(adminId), AuditAction.DEPOSIT_COMPLETED, "transaction:" + tx.getReference(),
                    "to " + accountNumber + " amount=" + amount);
            return new Outcome(toResponse(tx, customer.getBalance()), false);
        });
    }

    /**
     * The current state of a transfer the caller made: lets a client follow a held transfer to COMPLETED or REJECTED.
     * Transfers of other users are "not found".
     */
    public TransactionResponse find(long userId, String reference) {
        return transactions.findByReference(reference)
                .filter(t -> t.getType() == TransactionType.TRANSFER && Objects.equals(t.getInitiatedBy(), userId))
                .map(t -> replay(t.getId(), false).transaction())
                .orElseThrow(() -> ApiException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found."));
    }

    /**
     * Rebuilds the response from the stored transaction, so a retry gets the same answer. The status is the
     * current one: a retried held transfer shows COMPLETED once an admin approved it.
     */
    private Outcome replay(long transactionId, boolean replayed) {
        LedgerTransaction tx = transactions.findById(transactionId).orElseThrow();
        long actingAccount = tx.getType() == TransactionType.TRANSFER ? tx.getSourceAccountId() : tx.getDestinationAccountId();
        long balanceAfter = entries.findFirstByTransactionIdAndAccountIdOrderById(tx.getId(), actingAccount)
                .orElseThrow().getBalanceAfter();
        return new Outcome(toResponse(tx, balanceAfter), replayed);
    }

    private TransactionResponse toResponse(LedgerTransaction tx, long balanceAfter) {
        String source = accounts.findById(tx.getSourceAccountId()).orElseThrow().getAccountNumber();
        String destination = accounts.findById(tx.getDestinationAccountId()).orElseThrow().getAccountNumber();
        return new TransactionResponse(tx.getReference(), tx.getType(), tx.getStatus(), tx.getAmount(), source,
                destination, tx.getDescription(), balanceAfter, tx.getCreatedAt(), fraudReviews.reasons(tx.getId()));
    }

    private Instant startOfBusinessDay() {
        return LocalDate.now(clock.withZone(businessZone)).atStartOfDay(businessZone).toInstant();
    }

    private static String clean(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.strip().replaceAll("\\p{Cntrl}", " ");
    }

    /** Fingerprint of what the request asks for; the PIN is deliberately not part of it. */
    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
