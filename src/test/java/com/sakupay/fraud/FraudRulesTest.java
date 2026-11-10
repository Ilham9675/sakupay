package com.sakupay.fraud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRulesTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final Instant NOON = ZonedDateTime.of(2026, 11, 11, 12, 0, 0, 0, JAKARTA).toInstant();

    private final FraudRules rules = rules(LocalTime.of(0, 0), LocalTime.of(4, 0));

    @Test
    void smallTransfersToKnownPayeesPass() {
        assertThat(rules.evaluate(500_000, NOON, 0, true)).isEmpty();
        assertThat(rules.evaluate(4_999_999, NOON, 4, true)).isEmpty();
    }

    @Test
    void eachRuleFiresAtItsThreshold() {
        assertThat(rules.evaluate(5_000_000, NOON, 0, true)).containsExactly(FraudRule.LARGE_AMOUNT);
        assertThat(rules.evaluate(2_000_000, NOON, 0, false)).containsExactly(FraudRule.NEW_PAYEE_LARGE);
        assertThat(rules.evaluate(1_999_999, NOON, 0, false)).isEmpty();
        assertThat(rules.evaluate(1_000, NOON, 5, true)).containsExactly(FraudRule.HIGH_VELOCITY);
    }

    @ParameterizedTest
    @CsvSource({"23:59,false", "00:00,true", "02:30,true", "03:59,true", "04:00,false"})
    void theNightWindowIsMidnightToFourInJakarta(String time, boolean night) {
        Instant at = ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, JAKARTA).with(LocalTime.parse(time)).toInstant();
        assertThat(rules.isNight(at)).isEqualTo(night);
        assertThat(rules.evaluate(1_000_000, at, 0, true).contains(FraudRule.NIGHT_TIME)).isEqualTo(night);
        // Small amounts are fine even at night
        assertThat(rules.evaluate(999_999, at, 0, true)).isEmpty();
    }

    @Test
    void aWindowMayWrapAroundMidnightOrBeSwitchedOff() {
        FraudRules wrapping = rules(LocalTime.of(22, 0), LocalTime.of(4, 0));
        assertThat(wrapping.isNight(at("23:00"))).isTrue();
        assertThat(wrapping.isNight(at("03:00"))).isTrue();
        assertThat(wrapping.isNight(at("12:00"))).isFalse();

        FraudRules off = rules(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);
        assertThat(off.isNight(at("02:00"))).isFalse();
    }

    @Test
    void theAlertTakesTheMostSeriousRule() {
        assertThat(FraudRules.severity(List.of(FraudRule.NIGHT_TIME, FraudRule.NEW_PAYEE_LARGE)))
                .isEqualTo(FraudRule.Severity.MEDIUM);
        assertThat(FraudRules.severity(List.of(FraudRule.NIGHT_TIME, FraudRule.LARGE_AMOUNT)))
                .isEqualTo(FraudRule.Severity.HIGH);
    }

    private static FraudRules rules(LocalTime nightStart, LocalTime nightEnd) {
        return new FraudRules(new FraudProperties(5_000_000, 2_000_000, 5, Duration.ofMinutes(10),
                nightStart, nightEnd, 1_000_000), JAKARTA);
    }

    private static Instant at(String time) {
        return ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, JAKARTA).with(LocalTime.parse(time)).toInstant();
    }
}
