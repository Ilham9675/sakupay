package com.sakupay.fraud;

import com.sakupay.account.Account;
import com.sakupay.account.AccountLocks;
import com.sakupay.account.AccountRepository;
import com.sakupay.account.AccountType;
import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.common.PageResponse;
import com.sakupay.common.error.ApiException;
import com.sakupay.ledger.LedgerService;
import com.sakupay.ledger.LedgerTransaction;
import com.sakupay.ledger.LedgerTransactionRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Held transfers and their review. An admin approves (HOLD -> receiver) or rejects (HOLD -> sender, with a
 * mandatory reason). The alert row is locked first, so a decision is taken exactly once; an admin can never decide
 * on a transfer they started themselves.
 */
@Service
public class FraudReviewService {

    public static final String HOLD_ACCOUNT = "1000000002";

    private final FraudAlertRepository alerts;
    private final LedgerTransactionRepository transactions;
    private final AccountRepository accounts;
    private final AccountLocks locks;
    private final LedgerService ledger;
    private final AuditService audit;
    private final Clock clock;

    FraudReviewService(FraudAlertRepository alerts, LedgerTransactionRepository transactions, AccountRepository accounts,
                       AccountLocks locks, LedgerService ledger, AuditService audit, Clock clock) {
        this.alerts = alerts;
        this.transactions = transactions;
        this.accounts = accounts;
        this.locks = locks;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    public record AlertView(long id, String transactionReference, long amount, String sourceAccount,
                            String destinationAccount, List<FraudRule> rules, FraudRule.Severity severity,
                            FraudAlert.Status status, Instant createdAt, Long reviewedBy, String reviewNote,
                            Instant reviewedAt) {
    }

    public long holdAccountId() {
        return accounts.findIdByAccountNumberAndType(HOLD_ACCOUNT, AccountType.INTERNAL).orElseThrow();
    }

    /** Called inside the transfer's transaction, right after the money was moved to HOLD. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(long transactionId, List<FraudRule> hits) {
        alerts.save(new FraudAlert(transactionId, hits, Instant.now(clock)));
    }

    public List<FraudRule> reasons(long transactionId) {
        return alerts.findByTransactionId(transactionId).map(FraudAlert::getRules).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertView> list(FraudAlert.Status status, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        Page<FraudAlert> result = status == null
                ? alerts.findAllByOrderByIdDesc(request)
                : alerts.findByStatusOrderById(status, request);
        return PageResponse.of(result, this::view);
    }

    @Transactional
    public AlertView approve(long adminId, long alertId, String note) {
        FraudAlert alert = openAlert(alertId);
        LedgerTransaction tx = transactions.findById(alert.getTransactionId()).orElseThrow();
        requireNotOwn(adminId, tx);
        long hold = holdAccountId();
        AccountLocks.Locked locked = locks.lock(List.of(tx.getDestinationAccountId()), List.of(hold));
        Account receiver = locked.get(tx.getDestinationAccountId());
        if (!receiver.isActive()) {
            throw ApiException.conflict("DESTINATION_INACTIVE", "The receiving account can no longer receive money; reject instead.");
        }
        ledger.release(tx, locked.get(hold), receiver);
        alert.decide(FraudAlert.Status.APPROVED, adminId, clean(note), Instant.now(clock));
        audit.record(AuditService.user(adminId), AuditAction.FRAUD_ALERT_APPROVED, "transaction:" + tx.getReference(),
                "alert=" + alertId + " rules=" + alert.getRules());
        return view(alert);
    }

    @Transactional
    public AlertView reject(long adminId, long alertId, String note) {
        String reason = clean(note);
        if (reason == null) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Say why the transfer is rejected; the customer will ask.");
        }
        FraudAlert alert = openAlert(alertId);
        LedgerTransaction tx = transactions.findById(alert.getTransactionId()).orElseThrow();
        requireNotOwn(adminId, tx);
        long hold = holdAccountId();
        AccountLocks.Locked locked = locks.lock(List.of(tx.getSourceAccountId()), List.of(hold));
        ledger.giveBack(tx, locked.get(hold), locked.get(tx.getSourceAccountId()));
        alert.decide(FraudAlert.Status.REJECTED, adminId, reason, Instant.now(clock));
        audit.record(AuditService.user(adminId), AuditAction.FRAUD_ALERT_REJECTED, "transaction:" + tx.getReference(),
                "alert=" + alertId + " reason=" + reason);
        return view(alert);
    }

    private FraudAlert openAlert(long alertId) {
        FraudAlert alert = alerts.findByIdForUpdate(alertId)
                .orElseThrow(() -> ApiException.notFound("ALERT_NOT_FOUND", "Fraud alert not found."));
        if (alert.getStatus() != FraudAlert.Status.OPEN) {
            throw ApiException.conflict("ALERT_ALREADY_REVIEWED", "This alert was already " + alert.getStatus() + ".");
        }
        return alert;
    }

    private static void requireNotOwn(long adminId, LedgerTransaction tx) {
        if (tx.getInitiatedBy() != null && tx.getInitiatedBy() == adminId) {
            throw ApiException.conflict("SELF_REVIEW", "You cannot review a transfer you made yourself.");
        }
    }

    private AlertView view(FraudAlert a) {
        LedgerTransaction tx = transactions.findById(a.getTransactionId()).orElseThrow();
        return new AlertView(a.getId(), tx.getReference(), tx.getAmount(), number(tx.getSourceAccountId()),
                number(tx.getDestinationAccountId()), a.getRules(), a.getSeverity(), a.getStatus(), a.getCreatedAt(),
                a.getReviewedBy(), a.getReviewNote(), a.getReviewedAt());
    }

    private String number(long accountId) {
        return accounts.findById(accountId).orElseThrow().getAccountNumber();
    }

    private static String clean(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }
}
