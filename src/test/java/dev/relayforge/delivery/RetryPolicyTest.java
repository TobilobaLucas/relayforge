package dev.relayforge.delivery;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryPolicyTest {
    private final RetryPolicy policy = new RetryPolicy();

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void retriesTransientResponses(int status) {
        assertThat(RetryPolicy.retryable(status)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 301, 302, 400, 401, 403, 404, 410, 422, 600, 700})
    void doesNotRetryPermanentResponses(int status) {
        assertThat(RetryPolicy.retryable(status)).isFalse();
    }

    @Test
    void jitterIsBoundedAndRespectsRetryAfterWithinCap() {
        for (int i = 0; i < 100; i++) {
            assertThat(policy.delay(1, null).toMillis()).isBetween(1000L, 2000L);
            assertThat(policy.delay(20, null).toMillis()).isBetween(150000L, 300000L);
        }
        assertThat(policy.delay(1, Duration.ofSeconds(90))).isEqualTo(Duration.ofSeconds(90));
        assertThat(policy.delay(1, Duration.ofDays(1))).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void acceptsSecondsAndHttpDatesButIgnoresInvalidRetryAfter() {
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        assertThat(RetryPolicy.parseRetryAfter("30", now)).isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.parseRetryAfter("Wed, 7 Oct 2026 00:01:00 GMT", now))
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(RetryPolicy.parseRetryAfter("-1", now)).isEqualTo(Duration.ZERO);
        assertThat(RetryPolicy.parseRetryAfter("invalid", now)).isNull();
    }
}
