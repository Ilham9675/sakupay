package com.sakupay.fraud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** One held transfer waiting for (or decided by) an admin. */
@Entity
@Table(name = "fraud_alerts")
public class FraudAlert {

    public enum Status {
        OPEN, APPROVED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(nullable = false)
    private String rules;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FraudRule.Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected FraudAlert() {
    }

    FraudAlert(Long transactionId, List<FraudRule> hits, Instant createdAt) {
        this.transactionId = transactionId;
        this.rules = hits.stream().map(Enum::name).collect(Collectors.joining(","));
        this.severity = FraudRules.severity(hits);
        this.status = Status.OPEN;
        this.createdAt = createdAt;
    }

    void decide(Status decision, long adminId, String note, Instant now) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("Alert " + id + " was already " + status);
        }
        this.status = decision;
        this.reviewedBy = adminId;
        this.reviewNote = note;
        this.reviewedAt = now;
    }

    public List<FraudRule> getRules() {
        return Arrays.stream(rules.split(",")).map(FraudRule::valueOf).toList();
    }

    public Long getId() {
        return id;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public FraudRule.Severity getSeverity() {
        return severity;
    }

    public Status getStatus() {
        return status;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
