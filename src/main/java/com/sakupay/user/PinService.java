package com.sakupay.user;

import com.sakupay.audit.AuditAction;
import com.sakupay.audit.AuditService;
import com.sakupay.auth.SecurityProperties;
import com.sakupay.common.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * The 6-digit transaction PIN. It is hashed like a password, and after {@code pinMaxAttempts} wrong PINs in a row
 * it is locked for {@code pinLockDuration} (or until an admin unlocks it).
 */
@Service
public class PinService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public PinService(UserRepository users, PasswordEncoder encoder, SecurityProperties properties,
                      AuditService audit, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** Setting or changing the PIN needs the account password, so a stolen access token alone is not enough. */
    @Transactional
    public void setPin(long userId, String currentPassword, String newPin) {
        User user = users.findByIdForUpdate(userId).orElseThrow();
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.forbidden("WRONG_PASSWORD", "The password is not correct.");
        }
        if (isTrivial(newPin)) {
            throw ApiException.badRequest("WEAK_PIN", "Do not use repeated or sequential digits such as 111111 or 123456.");
        }
        user.changePin(encoder.encode(newPin));
        audit.record(AuditService.user(userId), AuditAction.PIN_SET, null, null);
    }

    /**
     * Checks a PIN. The user row is locked ({@code SELECT ... FOR UPDATE}), so parallel guesses are counted one
     * by one and cannot slip past the limit. The attempt is committed in its own transaction, even when the PIN
     * is wrong and even if a caller's surrounding transaction (e.g. a transfer) rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void verify(long userId, String pin) {
        Instant now = Instant.now(clock);
        User user = users.findByIdForUpdate(userId).orElseThrow();
        if (!user.hasPin()) {
            throw ApiException.conflict("PIN_NOT_SET", "Set a transaction PIN first.");
        }
        if (user.isPinLocked(now)) {
            throw locked(user);
        }
        if (encoder.matches(pin, user.getPinHash())) {
            user.pinAccepted();
            return;
        }
        String actor = AuditService.user(userId);
        boolean nowLocked = user.pinRejected(properties.pinMaxAttempts(), properties.pinLockDuration(), now);
        audit.record(actor, AuditAction.PIN_FAILED, null, "attempt=" + user.getPinFailedAttempts());
        if (nowLocked) {
            audit.record(actor, AuditAction.PIN_LOCKED, null, "until=" + user.getPinLockedUntil());
            throw locked(user);
        }
        throw ApiException.forbidden("WRONG_PIN", "The PIN is not correct.")
                .with("remainingAttempts", properties.pinMaxAttempts() - user.getPinFailedAttempts());
    }

    @Transactional
    public void unlock(long adminId, long userId) {
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found."));
        user.unlockPin();
        audit.record(AuditService.user(adminId), AuditAction.PIN_UNLOCKED, "user:" + userId, null);
    }

    private static ApiException locked(User user) {
        return new ApiException(HttpStatus.LOCKED, "PIN_LOCKED", "Too many wrong PINs. The PIN is temporarily locked.")
                .with("lockedUntil", user.getPinLockedUntil());
    }

    /** All digits the same (000000) or a running sequence (123456, 987654). */
    static boolean isTrivial(String pin) {
        boolean same = true;
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            int diff = pin.charAt(i) - pin.charAt(i - 1);
            same &= diff == 0;
            ascending &= diff == 1;
            descending &= diff == -1;
        }
        return same || ascending || descending;
    }
}
