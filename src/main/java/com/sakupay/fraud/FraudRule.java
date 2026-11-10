package com.sakupay.fraud;

/** The rules that can stop a transfer, each with how serious a hit is. */
public enum FraudRule {
    /** A single transfer above the large-amount threshold. */
    LARGE_AMOUNT(Severity.HIGH),
    /** A sizeable transfer to someone this account has never paid before (typical of scams and account takeover). */
    NEW_PAYEE_LARGE(Severity.MEDIUM),
    /** Many transfers in a short window (draining an account). */
    HIGH_VELOCITY(Severity.MEDIUM),
    /** A sizeable transfer in the middle of the night (Asia/Jakarta). */
    NIGHT_TIME(Severity.LOW);

    private final Severity severity;

    FraudRule(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }

    public enum Severity {
        LOW, MEDIUM, HIGH
    }
}
