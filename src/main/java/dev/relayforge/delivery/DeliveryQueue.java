package dev.relayforge.delivery;

import dev.relayforge.config.RelayProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DeliveryQueue {
    private final JdbcTemplate jdbc;
    private final RelayProperties properties;
    private final RetryPolicy retries;

    public DeliveryQueue(JdbcTemplate jdbc, RelayProperties properties, RetryPolicy retries) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.retries = retries;
    }

    @Transactional
    public List<DeliveryJob> claim(int limit) {
        if (limit < 1) return List.of();
        List<UUID> ids =
                jdbc.query(
                        """
                SELECT d.id FROM deliveries d JOIN endpoints e ON e.id = d.endpoint_id
                WHERE e.enabled AND d.cycle_attempts < ? AND
                  ((d.status = 'READY' AND d.next_attempt_at <= clock_timestamp())
                   OR (d.status = 'IN_FLIGHT' AND d.lease_until < clock_timestamp()))
                ORDER BY d.next_attempt_at, d.id LIMIT ? FOR UPDATE OF d SKIP LOCKED
                """,
                        (rs, n) -> rs.getObject(1, UUID.class),
                        properties.maxAttempts(),
                        Math.min(limit, properties.concurrency()));
        List<DeliveryJob> jobs = new ArrayList<>();
        for (UUID id : ids) {
            expireAttempt(id);
            UUID token = UUID.randomUUID();
            jdbc.update(
                    """
                    UPDATE deliveries SET status = 'IN_FLIGHT', lease_token = ?,
                      lease_until = clock_timestamp() + (? * interval '1 millisecond'),
                      attempt_count = attempt_count + 1, cycle_attempts = cycle_attempts + 1, updated_at = clock_timestamp()
                    WHERE id = ?
                    """,
                    token,
                    properties.leaseDuration().toMillis(),
                    id);
            DeliveryJob job =
                    jdbc.queryForObject(
                            """
                    SELECT d.*, e.url, e.encrypted_secret, ev.envelope FROM deliveries d
                    JOIN endpoints e ON e.id = d.endpoint_id JOIN events ev ON ev.id = d.event_id WHERE d.id = ?
                    """,
                            (rs, n) ->
                                    new DeliveryJob(
                                            rs.getObject("id", UUID.class),
                                            rs.getObject("event_id", UUID.class),
                                            rs.getObject("endpoint_id", UUID.class),
                                            rs.getObject("lease_token", UUID.class),
                                            rs.getInt("attempt_count"),
                                            rs.getInt("cycle_attempts"),
                                            rs.getString("url"),
                                            rs.getString("encrypted_secret"),
                                            rs.getString("envelope")),
                            id);
            jdbc.update(
                    "INSERT INTO delivery_attempts(id, delivery_id, attempt_number) VALUES (?, ?, ?)",
                    token,
                    id,
                    job.attemptNumber());
            jobs.add(job);
        }
        return List.copyOf(jobs);
    }

    @Transactional
    public int reapExhaustedWork() {
        List<UUID> ids =
                jdbc.query(
                        """
                SELECT id FROM deliveries WHERE cycle_attempts >= ? AND
                  (status = 'READY' OR (status = 'IN_FLIGHT' AND lease_until < clock_timestamp()))
                ORDER BY next_attempt_at, id LIMIT 100 FOR UPDATE SKIP LOCKED
                """,
                        (rs, n) -> rs.getObject(1, UUID.class),
                        properties.maxAttempts());
        for (UUID id : ids) {
            expireAttempt(id);
            jdbc.update(
                    """
                    UPDATE deliveries SET status = 'DEAD', lease_token = NULL, lease_until = NULL,
                      last_error = CASE WHEN status = 'IN_FLIGHT' THEN 'lease_expired' ELSE 'attempt_budget_exhausted' END,
                      updated_at = clock_timestamp() WHERE id = ?
                    """,
                    id);
        }
        return ids.size();
    }

    @Transactional
    public boolean complete(DeliveryJob job, DeliveryResult result) {
        List<UUID> owned =
                jdbc.query(
                        "SELECT id FROM deliveries WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ? FOR UPDATE",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        job.id(),
                        job.leaseToken());
        if (owned.isEmpty()) return false;
        String status =
                result.succeeded()
                        ? "SUCCEEDED"
                        : result.retryable() && job.cycleAttempts() < properties.maxAttempts()
                                ? "READY"
                                : "DEAD";
        Duration delay =
                status.equals("READY")
                        ? retries.delay(job.cycleAttempts(), result.retryAfter())
                        : Duration.ZERO;
        jdbc.update(
                """
                UPDATE deliveries SET status = ?, lease_token = NULL, lease_until = NULL,
                  next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                  last_http_status = ?, last_error = ?, updated_at = clock_timestamp() WHERE id = ?
                """,
                status,
                delay.toMillis(),
                result.httpStatus(),
                result.error(),
                job.id());
        jdbc.update(
                "UPDATE delivery_attempts SET outcome = ?, http_status = ?, duration_ms = ?, finished_at = clock_timestamp() WHERE id = ? AND outcome = 'IN_PROGRESS'",
                result.succeeded() ? "SUCCEEDED" : "FAILED",
                result.httpStatus(),
                result.durationMs(),
                job.leaseToken());
        return true;
    }

    private void expireAttempt(UUID deliveryId) {
        jdbc.update(
                "UPDATE delivery_attempts SET outcome = 'LEASE_EXPIRED', finished_at = clock_timestamp() WHERE delivery_id = ? AND outcome = 'IN_PROGRESS'",
                deliveryId);
    }
}
