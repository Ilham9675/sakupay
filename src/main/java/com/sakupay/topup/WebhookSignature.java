package com.sakupay.topup;

import com.sakupay.common.error.ApiException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies that a webhook really comes from the partner bank and is fresh.
 *
 * <p>The bank signs {@code "<timestamp>.<raw body>"} with HMAC-SHA256 and a shared secret, and sends
 * {@code X-Timestamp: <unix seconds>} and {@code X-Signature: sha256=<hex>}. Signing the timestamp together with
 * the body means an attacker can neither change the amount nor re-send an old request with a new timestamp.
 */
public final class WebhookSignature {

    static final String PREFIX = "sha256=";

    private WebhookSignature() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    /** Throws a 401 problem with a specific code when the request must be rejected. */
    static void verify(String secret, String timestamp, String body, String signature, Instant now, Duration tolerance) {
        if (timestamp == null || signature == null) {
            throw reject("SIGNATURE_MISSING", "X-Timestamp and X-Signature headers are required.");
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw reject("STALE_TIMESTAMP", "X-Timestamp must be unix seconds.");
        }
        // Checked before the signature: an old request is refused even if it was genuinely signed (replay)
        if (Math.abs(now.getEpochSecond() - sentAt) > tolerance.toSeconds()) {
            throw reject("STALE_TIMESTAMP", "The request is too old or from the future.");
        }
        byte[] expected = sign(secret, timestamp.trim(), body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        // Constant-time comparison: the response time does not reveal how many characters matched
        if (!MessageDigest.isEqual(expected, actual)) {
            throw reject("INVALID_SIGNATURE", "The signature does not match.");
        }
    }

    private static ApiException reject(String code, String message) {
        return ApiException.unauthorized(code, message);
    }
}
