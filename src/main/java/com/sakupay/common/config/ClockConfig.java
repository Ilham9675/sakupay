package com.sakupay.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Time comes from an injectable clock, so expiry and lockout rules can be tested without waiting.
 * It ticks in microseconds, the precision PostgreSQL stores: a timestamp returned right after a write is then
 * identical to the one read back later (e.g. when an idempotent request is replayed).
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }
}
