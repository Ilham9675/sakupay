package com.sakupay.ledger;

public enum TransactionType {
    /** Between two customer accounts, initiated by the owner of the source account. */
    TRANSFER,
    /** Cash paid in at a teller: SETTLEMENT to a customer account, initiated by an admin. */
    DEPOSIT
}
