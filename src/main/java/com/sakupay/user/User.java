package com.sakupay.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "pin_failed_attempts", nullable = false)
    private int pinFailedAttempts;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected User() {
    }

    public User(String fullName, String email, String phone, String passwordHash, Role role, Instant createdAt) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = createdAt;
    }

    // ---- PIN rules ----

    public boolean hasPin() {
        return pinHash != null;
    }

    public boolean isPinLocked(Instant now) {
        return pinLockedUntil != null && now.isBefore(pinLockedUntil);
    }

    /** A new PIN starts with a clean slate. */
    public void changePin(String newPinHash) {
        this.pinHash = newPinHash;
        unlockPin();
    }

    public void pinAccepted() {
        this.pinFailedAttempts = 0;
    }

    /**
     * Counts a wrong PIN. On the {@code maxAttempts}-th failure in a row the PIN is locked for {@code lockFor}.
     *
     * @return true if this failure locked the PIN
     */
    public boolean pinRejected(int maxAttempts, Duration lockFor, Instant now) {
        if (pinLockedUntil != null && !now.isBefore(pinLockedUntil)) {
            // The previous lock has expired: start counting again
            unlockPin();
        }
        pinFailedAttempts++;
        if (pinFailedAttempts >= maxAttempts) {
            pinLockedUntil = now.plus(lockFor);
            return true;
        }
        return false;
    }

    public void unlockPin() {
        this.pinFailedAttempts = 0;
        this.pinLockedUntil = null;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    // ---- getters ----

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPinHash() {
        return pinHash;
    }

    public int getPinFailedAttempts() {
        return pinFailedAttempts;
    }

    public Instant getPinLockedUntil() {
        return pinLockedUntil;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
