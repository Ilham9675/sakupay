package com.sakupay.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sakupay.common.error.ApiException;
import com.sakupay.common.ratelimit.RateLimitProperties.Limit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token-bucket rate limiting (Bucket4j). One bucket per (purpose, key), e.g. ("login-ip", "203.0.113.7"); buckets
 * live in a bounded cache and disappear after an hour without use, so an attacker cannot exhaust memory by
 * inventing keys.
 *
 * <p>The buckets are in this JVM's memory: right for one instance. With several instances behind a load balancer
 * each would count separately; the same Bucket4j API then runs on Redis (bucket4j-redis) without changing callers.
 */
@Component
public class RateLimiter {

    public static final String RETRY_AFTER = "retryAfterSeconds";

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /** Takes one token, or refuses the request with 429 and how long to wait. */
    public void check(String purpose, String key, Limit limit) {
        Bucket bucket = buckets.get(purpose + ":" + key, k -> Bucket.builder()
                .addLimit(l -> l.capacity(limit.requests()).refillGreedy(limit.requests(), limit.per()))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Please slow down.")
                    .with(RETRY_AFTER, seconds);
        }
    }
}
