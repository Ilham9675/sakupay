package com.sakupay.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Request budgets ({@code sakupay.rate-limit.*}): at most {@code requests} per {@code per}, refilled gradually. */
@ConfigurationProperties("sakupay.rate-limit")
public record RateLimitProperties(Limit loginPerIp, Limit loginPerEmail, Limit registerPerIp, Limit transfersPerUser) {

    public record Limit(int requests, Duration per) {
    }
}
