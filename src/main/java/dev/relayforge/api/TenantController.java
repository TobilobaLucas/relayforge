package dev.relayforge.api;

import dev.relayforge.api.ApiModels.*;
import dev.relayforge.delivery.DeliveryService;
import dev.relayforge.endpoint.EndpointService;
import dev.relayforge.event.EventService;
import dev.relayforge.project.ProjectService;
import dev.relayforge.security.Tenant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(
        name = "Project API",
        description = "Project API key required; all resources are scoped to that project")
public class TenantController {
    private final ProjectService projects;
    private final EndpointService endpoints;
    private final EventService events;
    private final DeliveryService deliveries;
    private final JdbcTemplate jdbc;

    public TenantController(
            ProjectService projects,
            EndpointService endpoints,
            EventService events,
            DeliveryService deliveries,
            JdbcTemplate jdbc) {
        this.projects = projects;
        this.endpoints = endpoints;
        this.events = events;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
    }

    @GetMapping("/project")
    public Project project(@AuthenticationPrincipal Tenant tenant) {
        return projects.get(tenant.projectId());
    }

    @PostMapping("/endpoints")
    @Operation(summary = "Register an endpoint; its signing secret is returned only once")
    public ResponseEntity<EndpointCredential> createEndpoint(
            @AuthenticationPrincipal Tenant tenant, @Valid @RequestBody CreateEndpoint request) {
        EndpointCredential result = endpoints.create(tenant.projectId(), request);
        return ResponseEntity.created(URI.create("/api/v1/endpoints/" + result.endpoint().id()))
                .body(result);
    }

    @GetMapping("/endpoints")
    public List<Endpoint> endpoints(@AuthenticationPrincipal Tenant tenant) {
        return endpoints.list(tenant.projectId());
    }

    @GetMapping("/endpoints/{id}")
    public Endpoint endpoint(@AuthenticationPrincipal Tenant tenant, @PathVariable UUID id) {
        return endpoints.get(tenant.projectId(), id);
    }

    @PatchMapping("/endpoints/{id}")
    @Operation(summary = "Pause or resume an endpoint")
    public Endpoint updateEndpoint(
            @AuthenticationPrincipal Tenant tenant,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEndpoint request) {
        return endpoints.update(tenant.projectId(), id, request.enabled());
    }

    @PostMapping("/events")
    @Operation(
            summary = "Atomically accept an event and enqueue its deliveries",
            description =
                    "Idempotency-Key is required. Identical retries return the original event; different content with the same key returns 409.")
    public ResponseEntity<EventReceipt> publish(
            @AuthenticationPrincipal Tenant tenant,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PublishEvent request) {
        EventReceipt result = events.publish(tenant.projectId(), key, request);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/events/" + result.eventId()))
                .body(result);
    }

    @GetMapping("/events/{id}")
    public Event event(@AuthenticationPrincipal Tenant tenant, @PathVariable UUID id) {
        return events.get(tenant.projectId(), id);
    }

    @GetMapping("/deliveries")
    @Operation(summary = "List deliveries using keyset pagination")
    public Page<Delivery> deliveries(
            @AuthenticationPrincipal Tenant tenant,
            @RequestParam(required = false) DeliveryService.Status status,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return deliveries.list(tenant.projectId(), status, eventId, cursor, limit);
    }

    @GetMapping("/deliveries/{id}")
    public Delivery delivery(@AuthenticationPrincipal Tenant tenant, @PathVariable UUID id) {
        return deliveries.get(tenant.projectId(), id);
    }

    @GetMapping("/deliveries/{id}/attempts")
    public List<Attempt> attempts(@AuthenticationPrincipal Tenant tenant, @PathVariable UUID id) {
        return deliveries.attempts(tenant.projectId(), id);
    }

    @PostMapping("/deliveries/{id}/replay")
    @Operation(
            summary =
                    "Replay a dead-letter delivery with the same delivery ID and full attempt history")
    public Delivery replay(@AuthenticationPrincipal Tenant tenant, @PathVariable UUID id) {
        return deliveries.replay(tenant.projectId(), id);
    }

    @GetMapping("/audit")
    @Operation(summary = "Read the project's audit history, newest first")
    public Page<AuditEntry> audit(
            @AuthenticationPrincipal Tenant tenant,
            @RequestParam(defaultValue = "9223372036854775807") @Min(1) long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<AuditEntry> rows =
                jdbc.query(
                        "SELECT id, action, resource_id, created_at FROM audit_log WHERE project_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                        (rs, n) ->
                                new AuditEntry(
                                        rs.getLong("id"),
                                        rs.getString("action"),
                                        rs.getObject("resource_id", UUID.class),
                                        rs.getTimestamp("created_at").toInstant()),
                        tenant.projectId(),
                        beforeId,
                        limit + 1);
        List<AuditEntry> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page<>(items, rows.size() > limit ? Long.toString(items.getLast().id()) : null);
    }
}
