package com.sakupay.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The number a customer pays into at the partner bank to top up one SakuPay account:
 * partner prefix {@value #PARTNER_PREFIX} followed by the account number.
 */
@Entity
@Table(name = "virtual_accounts")
public class VirtualAccount {

    public static final String PARTNER_PREFIX = "7008";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "va_number", nullable = false)
    private String vaNumber;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VirtualAccount() {
    }

    VirtualAccount(Account account, Instant createdAt) {
        this.vaNumber = numberFor(account.getAccountNumber());
        this.accountId = account.getId();
        this.createdAt = createdAt;
    }

    public static String numberFor(String accountNumber) {
        return PARTNER_PREFIX + accountNumber;
    }

    public String getVaNumber() {
        return vaNumber;
    }

    public Long getAccountId() {
        return accountId;
    }
}
