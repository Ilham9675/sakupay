package com.sakupay.account;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Locks accounts for a money movement in one global order: customer accounts first, by ascending id, then internal
 * accounts (SETTLEMENT, HOLD), also by ascending id. Every code path that moves money follows it, so no two
 * movements can ever wait for each other in a circle (deadlock): A->B against B->A, a transfer put on hold
 * against an admin releasing another hold, a top-up against a transfer.
 */
@Component
public class AccountLocks {

    private final AccountRepository accounts;

    public AccountLocks(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Locks the given customer accounts, then the given internal accounts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Locked lock(Collection<Long> customerIds, Collection<Long> internalIds) {
        Locked locked = new Locked(new HashMap<>());
        for (long id : new TreeSet<>(customerIds)) {
            locked.add(accounts.findByIdForUpdate(id).orElseThrow());
        }
        for (long id : new TreeSet<>(internalIds)) {
            locked.add(accounts.findByIdForUpdate(id).orElseThrow());
        }
        return locked;
    }

    /**
     * Adds one internal account to a set of already locked customer accounts. Allowed because internal accounts
     * always come last in the order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Account lockInternal(long internalId) {
        return accounts.findByIdForUpdate(internalId).orElseThrow();
    }

    public record Locked(Map<Long, Account> byId) {

        private void add(Account account) {
            byId.put(account.getId(), account);
        }

        public Account get(long id) {
            Account account = byId.get(id);
            if (account == null) {
                throw new IllegalStateException("Account " + id + " was not locked");
            }
            return account;
        }
    }
}
