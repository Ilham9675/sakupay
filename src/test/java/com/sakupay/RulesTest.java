package com.sakupay;

import com.sakupay.account.AccountNumbers;
import com.sakupay.auth.SecurityProperties;
import com.sakupay.common.error.ApiException;
import com.sakupay.user.PhoneNumbers;
import com.sakupay.user.Role;
import com.sakupay.user.User;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit tests for the rules that do not need a database. */
class RulesTest {

    @Test
    void accountNumbersCarryALuhnCheckDigitThatCatchesTypos() {
        Random random = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            String number = AccountNumbers.generate(random);
            assertThat(number).hasSize(10).startsWith("88");
            assertThat(AccountNumbers.isValid(number)).isTrue();

            // Any single wrong digit is detected
            int pos = random.nextInt(10);
            char wrong = (char) ('0' + (number.charAt(pos) - '0' + 1 + random.nextInt(9)) % 10);
            String typo = number.substring(0, pos) + wrong + number.substring(pos + 1);
            assertThat(AccountNumbers.isValid(typo)).as(number + " -> " + typo).isFalse();
        }
        assertThat(AccountNumbers.isValid("88123")).isFalse();
        assertThat(AccountNumbers.isValid("88abcdefgh")).isFalse();
        assertThat(AccountNumbers.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"081234567890", "0812-3456-7890", "+62 812 3456 7890", "6281234567890"})
    void phoneNumbersAreNormalisedToE164(String raw) {
        assertThat(PhoneNumbers.normalize(raw)).isEqualTo("+6281234567890");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "0212345678", "+6512345678", "08123"})
    void nonMobileNumbersAreRejected(String raw) {
        assertThatThrownBy(() -> PhoneNumbers.normalize(raw))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_PHONE");
    }

    @Test
    void aPinLockExpiresAndThenCountingStartsAgain() {
        User user = new User("A", "a@test.local", null, "hash", Role.USER, Instant.EPOCH);
        user.changePin("pin-hash");
        Instant t = Instant.parse("2026-11-11T08:00:00Z");
        Duration lockFor = Duration.ofMinutes(30);

        for (int i = 1; i < 5; i++) {
            assertThat(user.pinRejected(5, lockFor, t)).isFalse();
        }
        assertThat(user.pinRejected(5, lockFor, t)).isTrue();
        assertThat(user.isPinLocked(t.plus(Duration.ofMinutes(29)))).isTrue();
        assertThat(user.isPinLocked(t.plus(lockFor))).isFalse();

        // After the lock, one mistake is one mistake, not an immediate new lock
        assertThat(user.pinRejected(5, lockFor, t.plus(lockFor))).isFalse();
        assertThat(user.getPinFailedAttempts()).isEqualTo(1);
    }

    @Test
    void theAppRefusesToStartWithAWeakJwtSecret() {
        assertThatThrownBy(() -> new SecurityProperties("too-short", Duration.ofMinutes(15), Duration.ofDays(7),
                10, 5, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new SecurityProperties(null, Duration.ofMinutes(15), Duration.ofDays(7),
                10, 5, Duration.ofMinutes(30)))
                .isInstanceOf(IllegalStateException.class);
        SecurityProperties ok = new SecurityProperties("x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7),
                10, 5, Duration.ofMinutes(30));
        assertThat(ok.toString()).doesNotContain("xxxx").contains("****");
    }
}
