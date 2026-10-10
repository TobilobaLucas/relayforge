package dev.relayforge.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record CreateProject(@NotBlank @Size(max = 120) String name) {}

    public record Project(UUID id, String name, Instant createdAt) {}

    public record ProjectCredential(Project project, String apiKey) {}

    public record CreateEndpoint(
            @NotBlank @Size(max = 2048) String url,
            @NotBlank @Size(max = 240) String description,
            @NotNull @Size(min = 1, max = 20)
                    Set<@Pattern(regexp = "[a-z][a-z0-9_.-]{0,99}") String> eventTypes) {}

    public record Endpoint(
            UUID id,
            String url,
            String description,
            Set<String> eventTypes,
            boolean enabled,
            Instant createdAt) {}

    public record EndpointCredential(Endpoint endpoint, String signingSecret) {}

    public record UpdateEndpoint(@NotNull Boolean enabled) {}

    public record PublishEvent(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{0,99}") String type,
            @NotNull JsonNode data) {}

    public record EventReceipt(UUID eventId, int deliveryCount, boolean duplicate) {}

    public record Event(UUID id, JsonNode envelope, Instant createdAt) {}

    public record Delivery(
            UUID id,
            UUID eventId,
            UUID endpointId,
            String status,
            int attemptCount,
            int replayCount,
            Instant nextAttemptAt,
            Integer lastHttpStatus,
            String lastError,
            Instant createdAt,
            Instant updatedAt) {}

    public record Attempt(
            int attemptNumber,
            String outcome,
            Integer httpStatus,
            Long durationMs,
            Instant startedAt,
            Instant finishedAt) {}

    public record Page<T>(List<T> items, String nextCursor) {}

    public record AuditEntry(long id, String action, UUID resourceId, Instant createdAt) {}
}
