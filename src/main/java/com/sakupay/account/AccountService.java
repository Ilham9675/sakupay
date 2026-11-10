package com.sakupay.account;

import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.common.error.ApiException;
import com.sakupay.user.User;
import com.sakupay.user.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NUMBER_ATTEMPTS = 10;

    private final AccountRepository accounts;
    private final VirtualAccountRepository virtualAccounts;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;
    private final int maxPerUser;
    private final long defaultDailyLimit;

    AccountService(AccountRepository accounts, VirtualAccountRepository virtualAccounts, UserRepository users, AuditService audit, Clock clock,
                   @Value("${sakupay.accounts.max-per-user}") int maxPerUser,
                   @Value("${sakupay.accounts.default-daily-limit}") long defaultDailyLimit) {
        this.accounts = accounts;
        this.virtualAccounts = virtualAccounts;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.maxPerUser = maxPerUser;
        this.defaultDailyLimit = defaultDailyLimit;
    }

    /**
     * Opens an account. The user row is locked first, so two parallel requests cannot both pass the
     * "at most N accounts" check.
     */
    @Transactional
    public Account open(long userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow();
        if (!user.hasPin()) {
            throw ApiException.conflict("PIN_NOT_SET", "Set a transaction PIN before opening an account.");
        }
        if (accounts.countByOwnerId(userId) >= maxPerUser) {
            throw ApiException.conflict("ACCOUNT_LIMIT_REACHED", "You can have at most " + maxPerUser + " accounts.");
        }
        Instant now = Instant.now(clock);
        Account account = accounts.save(new Account(newAccountNumber(), userId, defaultDailyLimit, now));
        virtualAccounts.save(new VirtualAccount(account, now));
        audit.record(AuditService.user(userId), AuditAction.ACCOUNT_OPENED, "account:" + account.getAccountNumber(), null);
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> list(long userId) {
        return accounts.findByOwnerIdOrderById(userId);
    }

    /** Someone else's account answers exactly like a non-existent one (404), so numbers cannot be probed. */
    @Transactional(readOnly = true)
    public Account get(long userId, String accountNumber) {
        return accounts.findByAccountNumberAndOwnerId(accountNumber, userId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found."));
    }

    private String newAccountNumber() {
        for (int i = 0; i < NUMBER_ATTEMPTS; i++) {
            String candidate = AccountNumbers.generate(RANDOM);
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free account number");
    }
}
