package com.sakupay.transfer;

import com.sakupay.common.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Claims an idempotency key inside the caller's database transaction.
 *
 * <p>{@code INSERT ... ON CONFLICT DO NOTHING} on the unique (user, key) index does the hard part: when two requests
 * with the same key arrive together, PostgreSQL makes the second INSERT wait until the first transaction ends.
 * If the first committed, the second sees its row and replays its result; if it rolled back (e.g. insufficient
 * funds), the second simply proceeds. No "in progress" state, no polling.
 */
@Component
class IdempotencyStore {

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    IdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static void validate(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (e.g. a UUID) so a retried request cannot move money twice.");
        }
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "The Idempotency-Key must be 8 to 100 letters, digits, '-' or '_'.");
        }
    }

    /**
     * @return empty if this request owns the key now; otherwise the transaction created by the earlier request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> claim(long userId, String key, String requestHash) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (user_id, idem_key, request_hash, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, idem_key) DO NOTHING
                """, userId, key, requestHash, Timestamp.from(Instant.now(clock)));
        if (inserted == 1) {
            return Optional.empty();
        }
        var existing = jdbc.queryForMap("SELECT request_hash, transaction_id FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                userId, key);
        if (!requestHash.equals(existing.get("request_hash"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request.");
        }
        return Optional.of(((Number) existing.get("transaction_id")).longValue());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void complete(long userId, String key, long transactionId) {
        jdbc.update("UPDATE idempotency_keys SET transaction_id = ? WHERE user_id = ? AND idem_key = ?",
                transactionId, userId, key);
    }
}
