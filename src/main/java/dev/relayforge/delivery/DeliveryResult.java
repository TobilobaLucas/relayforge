package dev.relayforge.delivery;

import java.time.Duration;

public record DeliveryResult(
        Integer httpStatus, String error, boolean retryable, Duration retryAfter, long durationMs) {
    public boolean succeeded() {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    public static DeliveryResult failure(String error, boolean retryable, long durationMs) {
        return new DeliveryResult(null, error, retryable, null, durationMs);
    }
}
