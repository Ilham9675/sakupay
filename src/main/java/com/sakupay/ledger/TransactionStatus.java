package com.sakupay.ledger;

public enum TransactionStatus {
    COMPLETED,
    /** Stopped by a fraud rule: the amount sits in the HOLD account until an admin decides. */
    ON_HOLD,
    /** An admin rejected the held transfer; the amount went back to the sender. */
    REJECTED
}
