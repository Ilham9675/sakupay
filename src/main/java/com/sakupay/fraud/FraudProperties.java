package com.sakupay.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Fraud rule thresholds ({@code sakupay.fraud.*}). Amounts are rupiah. The night window is
 * [{@code nightStart}, {@code nightEnd}) in the business time zone; equal start and end switch the rule off.
 */
@ConfigurationProperties("sakupay.fraud")
public record FraudProperties(
        long largeAmount,
        long newPayeeAmount,
        int velocityMaxTransfers,
        Duration velocityWindow,
        LocalTime nightStart,
        LocalTime nightEnd,
        long nightAmount) {
}
