package com.sakupay.account;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks two accounts for a money movement, always the lower id first. Every code path that moves money uses this,
 * so two movements touching the same pair (A->B and B->A, or a transfer and a top-up) wait for each other
 * instead of deadlocking.
 */
@Component
public class AccountLocks {

    private final AccountRepository accounts;

    public AccountLocks(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Pair lock(long first, long second) {
        long low = Math.min(first, second);
        long high = Math.max(first, second);
        Account lowAccount = accounts.findByIdForUpdate(low).orElseThrow();
        Account highAccount = accounts.findByIdForUpdate(high).orElseThrow();
        return new Pair(lowAccount, highAccount);
    }

    public record Pair(Account a, Account b) {
        public Account get(long id) {
            return a.getId() == id ? a : b;
        }
    }
}
