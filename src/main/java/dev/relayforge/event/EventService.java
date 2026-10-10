package dev.relayforge.event;

import dev.relayforge.api.ApiModels.Event;
import dev.relayforge.api.ApiModels.EventReceipt;
import dev.relayforge.api.ApiModels.PublishEvent;
import dev.relayforge.common.ApiException;
import dev.relayforge.common.JsonCodec;
import dev.relayforge.config.RelayProperties;
import dev.relayforge.project.ProjectService;
import dev.relayforge.security.Credentials;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final JsonCodec json;
    private final RelayProperties properties;
    private final Clock clock;

    public EventService(
            JdbcTemplate jdbc,
            ProjectService projects,
            JsonCodec json,
            RelayProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public EventReceipt publish(UUID projectId, String idempotencyKey, PublishEvent request) {
        if (idempotencyKey == null || !idempotencyKey.matches("[a-zA-Z0-9._:-]{1,128}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_idempotency_key",
                    "Supply an Idempotency-Key header with 1–128 letters, digits, periods, underscores, colons, or hyphens.");
        }
        if (!request.data().isObject()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_event_data",
                    "Event data must be a JSON object.");
        }
        String canonical = json.write(json.canonical(json.read(json.write(request))));
        if (canonical.getBytes(StandardCharsets.UTF_8).length > properties.maxPayloadBytes()) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "payload_too_large",
                    "The event payload exceeds the size limit.");
        }
        String hash = Credentials.hash(canonical);
        projects.lock(projectId);
        List<Existing> existing =
                jdbc.query(
                        "SELECT id, request_hash FROM events WHERE project_id = ? AND idempotency_key = ?",
                        (rs, n) ->
                                new Existing(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("request_hash")),
                        projectId,
                        idempotencyKey);
        if (!existing.isEmpty()) {
            Existing event = existing.getFirst();
            if (!hash.equals(event.hash()))
                throw ApiException.conflict(
                        "idempotency_conflict",
                        "This idempotency key was already used for a different event.");
            return new EventReceipt(event.id(), deliveryCount(event.id()), true);
        }
        List<UUID> endpoints =
                jdbc.query(
                        "SELECT id FROM endpoints WHERE project_id = ? AND enabled AND jsonb_exists(event_types, ?)",
                        (rs, n) -> rs.getObject("id", UUID.class),
                        projectId,
                        request.type());
        projects.reserveCapacity(projectId, endpoints.size());
        projects.consumeEventQuota(projectId);
        UUID id = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("type", request.type());
        envelope.put("createdAt", clock.instant());
        envelope.put("data", request.data());
        jdbc.update(
                "INSERT INTO events(id, project_id, event_type, idempotency_key, request_hash, envelope) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                projectId,
                request.type(),
                idempotencyKey,
                hash,
                json.write(envelope));
        for (UUID endpointId : endpoints) {
            jdbc.update(
                    "INSERT INTO deliveries(id, project_id, event_id, endpoint_id) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(),
                    projectId,
                    id,
                    endpointId);
        }
        projects.audit(projectId, "event.accepted", id);
        return new EventReceipt(id, endpoints.size(), false);
    }

    public Event get(UUID projectId, UUID id) {
        return jdbc
                .query(
                        "SELECT id, envelope, created_at FROM events WHERE project_id = ? AND id = ?",
                        (rs, n) ->
                                new Event(
                                        rs.getObject("id", UUID.class),
                                        json.read(rs.getString("envelope")),
                                        rs.getTimestamp("created_at").toInstant()),
                        projectId,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    private int deliveryCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM deliveries WHERE event_id = ?", Integer.class, eventId);
    }

    private record Existing(UUID id, String hash) {}
}
