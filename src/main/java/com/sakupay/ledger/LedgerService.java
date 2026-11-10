package com.sakupay.ledger;

import com.sakupay.account.Account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The only place where balances change. Every posting writes one DEBIT and one CREDIT of the same amount and
 * updates both stored balances in the same database transaction.
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

    /**
     * Moves {@code amount} from {@code from} to {@code to}. Both accounts must already be locked by the caller
     * ({@code SELECT ... FOR UPDATE}), so the balances used here are current.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(TransactionType type, Account from, Account to, long amount, String description,
                                  long initiatedBy) {
        Instant now = Instant.now(clock);
        from.debit(amount);
        to.credit(amount);
        LedgerTransaction tx = transactions.save(new LedgerTransaction(type, amount, from.getId(), to.getId(),
                description, initiatedBy, now));
        entries.save(new LedgerEntry(tx.getId(), from.getId(), EntryDirection.DEBIT, amount, from.getBalance(), now));
        entries.save(new LedgerEntry(tx.getId(), to.getId(), EntryDirection.CREDIT, amount, to.getBalance(), now));
        return tx;
    }
}
