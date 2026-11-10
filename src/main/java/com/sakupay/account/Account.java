package com.sakupay.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A rupiah account. The balance is a whole number of rupiah and only changes through ledger postings
 * ({@link #debit} / {@link #credit}), always while the row is locked.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "user_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private long balance;

    @Column(name = "daily_transfer_limit", nullable = false)
    private long dailyTransferLimit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Account() {
    }

    Account(String accountNumber, Long ownerId, long dailyTransferLimit, Instant createdAt) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.currency = "IDR";
        this.balance = 0;
        this.dailyTransferLimit = dailyTransferLimit;
        this.status = AccountStatus.ACTIVE;
        this.type = AccountType.CUSTOMER;
        this.createdAt = createdAt;
    }

    /** Takes money out. A customer account can never go below zero (the database enforces it too). */
    public void debit(long amount) {
        requirePositive(amount);
        if (type == AccountType.CUSTOMER && balance < amount) {
            throw new IllegalStateException("Insufficient balance on " + accountNumber);
        }
        balance = Math.subtractExact(balance, amount);
    }

    /** Puts money in. */
    public void credit(long amount) {
        requirePositive(amount);
        balance = Math.addExact(balance, amount);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public long getDailyTransferLimit() {
        return dailyTransferLimit;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public AccountType getType() {
        return type;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
