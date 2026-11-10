package com.sakupay.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One business event that moves money (a transfer, a deposit). Its ledger entries always balance. */
@Entity
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false)
    private long amount;

    @Column(name = "source_account_id", nullable = false)
    private Long sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private Long destinationAccountId;

    private String description;

    /** The user who started it; null for TOPUP (the partner bank did). */
    @Column(name = "initiated_by")
    private Long initiatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected LedgerTransaction() {
    }

    LedgerTransaction(TransactionType type, TransactionStatus status, long amount, Long sourceAccountId,
                      Long destinationAccountId, String description, Long initiatedBy, Instant createdAt) {
        this.reference = UUID.randomUUID().toString();
        this.type = type;
        this.status = status;
        this.amount = amount;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.description = description;
        this.initiatedBy = initiatedBy;
        this.createdAt = createdAt;
    }

    /** A held transaction is either released to the receiver or returned; nothing else can change its status. */
    void complete(Instant now) {
        requireOnHold();
        this.status = TransactionStatus.COMPLETED;
        this.updatedAt = now;
    }

    void reject(Instant now) {
        requireOnHold();
        this.status = TransactionStatus.REJECTED;
        this.updatedAt = now;
    }

    private void requireOnHold() {
        if (status != TransactionStatus.ON_HOLD) {
            throw new IllegalStateException("Transaction " + reference + " is " + status + ", not ON_HOLD");
        }
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public long getAmount() {
        return amount;
    }

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public Long getDestinationAccountId() {
        return destinationAccountId;
    }

    public String getDescription() {
        return description;
    }

    public Long getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
