package com.sakupay.topup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Webhook settings ({@code sakupay.topup.*}); the shared secret comes from {@code TOPUP_WEBHOOK_SECRET}. */
@ConfigurationProperties("sakupay.topup")
public record TopupProperties(String webhookSecret, Duration timestampTolerance) {

    public TopupProperties {
        if (webhookSecret == null || webhookSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("TOPUP_WEBHOOK_SECRET must be set to at least 32 bytes.");
        }
    }

    @Override
    public String toString() {
        return "TopupProperties[webhookSecret=****, timestampTolerance=" + timestampTolerance + "]";
    }
}
