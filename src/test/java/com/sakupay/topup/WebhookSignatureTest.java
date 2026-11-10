package com.sakupay.topup;

import com.sakupay.common.error.ApiException;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureTest {

    private static final String SECRET = "a-shared-secret-of-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @Test
    void theSignatureCoversTimestampAndBody() {
        String ts = Long.toString(NOW.getEpochSecond());
        String sig = WebhookSignature.sign(SECRET, ts, "{\"amount\":1}");

        assertThat(sig).startsWith("sha256=").hasSize(7 + 64);
        assertThat(WebhookSignature.sign(SECRET, ts, "{\"amount\":2}")).isNotEqualTo(sig);
        assertThat(WebhookSignature.sign(SECRET, Long.toString(NOW.getEpochSecond() + 1), "{\"amount\":1}")).isNotEqualTo(sig);
        assertThatCode(() -> WebhookSignature.verify(SECRET, ts, "{\"amount\":1}", sig.toUpperCase().replace("SHA256=", "sha256="),
                NOW, FIVE_MINUTES)).doesNotThrowAnyException();
    }

    @Test
    void theTimestampWindowIsFiveMinutesEitherWay() {
        assertThatCode(() -> verifyAt(NOW.minus(FIVE_MINUTES))).doesNotThrowAnyException();
        assertThatCode(() -> verifyAt(NOW.plus(FIVE_MINUTES))).doesNotThrowAnyException();
        assertThatThrownBy(() -> verifyAt(NOW.minus(FIVE_MINUTES).minusSeconds(1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STALE_TIMESTAMP");
        assertThatThrownBy(() -> verifyAt(NOW.plus(FIVE_MINUTES).plusSeconds(1)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STALE_TIMESTAMP");
    }

    private static void verifyAt(Instant sentAt) {
        String ts = Long.toString(sentAt.getEpochSecond());
        WebhookSignature.verify(SECRET, ts, "{}", WebhookSignature.sign(SECRET, ts, "{}"), NOW, FIVE_MINUTES);
    }
}
