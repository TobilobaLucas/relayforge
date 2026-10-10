package dev.relayforge.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("relay")
public record RelayProperties(
        @NotBlank @Size(min = 32, max = 128) String adminToken,
        @NotBlank String encryptionKey,
        @Min(1) @Max(128) int concurrency,
        @Min(1) @Max(20) int maxAttempts,
        Duration leaseDuration,
        Duration requestTimeout,
        @Min(1) int maxPendingPerProject,
        @Min(1) int eventsPerMinute,
        @Min(1) int maxPayloadBytes,
        Set<String> developmentHosts) {
    public RelayProperties {
        developmentHosts = developmentHosts == null ? Set.of() : Set.copyOf(developmentHosts);
        if (leaseDuration == null
                || requestTimeout == null
                || requestTimeout.isNegative()
                || requestTimeout.isZero()
                || leaseDuration.compareTo(requestTimeout.multipliedBy(3)) < 0) {
            throw new IllegalArgumentException(
                    "The lease must be at least three times the request timeout");
        }
    }
}
