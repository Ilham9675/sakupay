package com.sakupay.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Security settings ({@code sakupay.security.*}). The JWT secret comes from the {@code JWT_SECRET}
 * environment variable; the app refuses to start without a strong one.
 */
@ConfigurationProperties("sakupay.security")
public record SecurityProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int bcryptStrength,
        int pinMaxAttempts,
        Duration pinLockDuration) {

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least " + MIN_SECRET_BYTES + " bytes (e.g. `openssl rand -base64 48`).");
        }
    }

    @Override
    public String toString() {
        // Never print the secret, e.g. in a startup log or an actuator dump
        return "SecurityProperties[jwtSecret=****, accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl="
                + refreshTokenTtl + ", bcryptStrength=" + bcryptStrength + ", pinMaxAttempts=" + pinMaxAttempts
                + ", pinLockDuration=" + pinLockDuration + "]";
    }
}
