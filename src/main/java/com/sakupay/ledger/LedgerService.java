package com.sakupay.ledger;

import com.sakupay.account.Account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The only place where balances change. Every movement writes one DEBIT and one CREDIT of the same amount and
 * updates both stored balances in the same database transaction. All accounts passed in must already be locked
 * by the caller ({@code SELECT ... FOR UPDATE}, see AccountLocks), so the balances used here are current.
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerService(LedgerTransactionRepository transactions, LedgerEntryRepository entries, Clock clock) {
        this.transactions = transactions;
        this.entries = entries;
        this.clock = clock;
    }

    /** Moves {@code amount} from {@code from} to {@code to} in one completed transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(TransactionType type, Account from, Account to, long amount, String description,
                                  Long initiatedBy) {
        Instant now = Instant.now(clock);
        LedgerTransaction tx = transactions.save(new LedgerTransaction(type, TransactionStatus.COMPLETED, amount,
                from.getId(), to.getId(), description, initiatedBy, now));
        move(tx, from, to, amount, now);
        return tx;
    }

    /**
     * A transfer stopped by a fraud rule: the amount leaves the sender (so it cannot be spent again) and waits in
     * the HOLD account. The transaction still names the intended receiver.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction hold(Account from, Account holdAccount, Account intendedReceiver, long amount,
                                  String description, Long initiatedBy) {
        Instant now = Instant.now(clock);
        LedgerTransaction tx = transactions.save(new LedgerTransaction(TransactionType.TRANSFER,
                TransactionStatus.ON_HOLD, amount, from.getId(), intendedReceiver.getId(), description, initiatedBy, now));
        move(tx, from, holdAccount, amount, now);
        return tx;
    }

    /** Approved: HOLD -> receiver, as more entries of the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(LedgerTransaction tx, Account holdAccount, Account receiver) {
        Instant now = Instant.now(clock);
        tx.complete(now);
        move(tx, holdAccount, receiver, tx.getAmount(), now);
    }

    /** Rejected: HOLD -> sender. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void giveBack(LedgerTransaction tx, Account holdAccount, Account sender) {
        Instant now = Instant.now(clock);
        tx.reject(now);
        move(tx, holdAccount, sender, tx.getAmount(), now);
    }

    private void move(LedgerTransaction tx, Account from, Account to, long amount, Instant now) {
        from.debit(amount);
        to.credit(amount);
        entries.save(new LedgerEntry(tx.getId(), from.getId(), EntryDirection.DEBIT, amount, from.getBalance(), now));
        entries.save(new LedgerEntry(tx.getId(), to.getId(), EntryDirection.CREDIT, amount, to.getBalance(), now));
    }
}
