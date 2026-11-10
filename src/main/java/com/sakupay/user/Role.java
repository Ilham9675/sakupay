package com.sakupay.user;

/**
 * USER owns accounts and moves money. ADMIN manages users (roles, PIN unlocks) but cannot read the audit trail.
 * AUDITOR can only read the audit trail. Keeping ADMIN and AUDITOR apart is a separation-of-duties control.
 */
public enum Role {
    USER,
    ADMIN,
    AUDITOR
}
