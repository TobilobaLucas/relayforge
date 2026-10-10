package dev.relayforge.delivery;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
    public static boolean retryable(int status) {
        return status == 408 || status == 429 || (status >= 500 && status < 600);
    }

    public Duration delay(int cycleAttempts, Duration retryAfter) {
        long ceiling = Math.min(300_000L, 2000L << Math.min(17, Math.max(0, cycleAttempts - 1)));
        long jitter = ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
        long requested =
                retryAfter == null ? 0 : Math.max(0, Math.min(300_000, retryAfter.toMillis()));
        return Duration.ofMillis(Math.max(jitter, requested));
    }

    public static Duration parseRetryAfter(String header, Instant now) {
        if (header == null || header.length() > 100) return null;
        try {
            long seconds = Long.parseLong(header.trim());
            return Duration.ofSeconds(Math.min(300, Math.max(0, seconds)));
        } catch (NumberFormatException ignored) {
            try {
                Instant target =
                        ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant();
                return Duration.ofSeconds(
                        Math.min(300, Math.max(0, Duration.between(now, target).getSeconds())));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
