package dev.relayforge.delivery;

import dev.relayforge.api.ApiModels.Attempt;
import dev.relayforge.api.ApiModels.Delivery;
import dev.relayforge.api.ApiModels.Page;
import dev.relayforge.common.ApiException;
import dev.relayforge.common.PageCursor;
import dev.relayforge.project.ProjectService;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {
    public enum Status {
        READY,
        IN_FLIGHT,
        SUCCEEDED,
        DEAD
    }

    private static final RowMapper<Delivery> DELIVERY =
            (rs, n) ->
                    new Delivery(
                            rs.getObject("id", UUID.class),
                            rs.getObject("event_id", UUID.class),
                            rs.getObject("endpoint_id", UUID.class),
                            rs.getString("status"),
                            rs.getInt("attempt_count"),
                            rs.getInt("replay_count"),
                            rs.getTimestamp("next_attempt_at").toInstant(),
                            rs.getObject("last_http_status", Integer.class),
                            rs.getString("last_error"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    private final ProjectService projects;

    public DeliveryService(JdbcTemplate jdbc, ProjectService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    public Delivery get(UUID projectId, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM deliveries WHERE project_id = ? AND id = ?",
                        DELIVERY,
                        projectId,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    public Page<Delivery> list(
            UUID projectId, Status status, UUID eventId, String cursorValue, int limit) {
        PageCursor cursor = PageCursor.decode(cursorValue);
        StringBuilder sql = new StringBuilder("SELECT * FROM deliveries WHERE project_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(projectId);
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status.name());
        }
        if (eventId != null) {
            sql.append(" AND event_id = ?");
            parameters.add(eventId);
        }
        if (cursor != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            parameters.add(Timestamp.from(cursor.createdAt()));
            parameters.add(cursor.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        parameters.add(limit + 1);
        List<Delivery> rows = jdbc.query(sql.toString(), DELIVERY, parameters.toArray());
        boolean hasMore = rows.size() > limit;
        List<Delivery> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next =
                hasMore
                        ? new PageCursor(items.getLast().createdAt(), items.getLast().id()).encode()
                        : null;
        return new Page<>(items, next);
    }

    public List<Attempt> attempts(UUID projectId, UUID id) {
        get(projectId, id);
        return jdbc.query(
                "SELECT * FROM delivery_attempts WHERE delivery_id = ? ORDER BY attempt_number",
                (rs, n) ->
                        new Attempt(
                                rs.getInt("attempt_number"),
                                rs.getString("outcome"),
                                rs.getObject("http_status", Integer.class),
                                rs.getObject("duration_ms", Long.class),
                                rs.getTimestamp("started_at").toInstant(),
                                rs.getTimestamp("finished_at") == null
                                        ? null
                                        : rs.getTimestamp("finished_at").toInstant()),
                id);
    }

    @Transactional
    public Delivery replay(UUID projectId, UUID id) {
        projects.lock(projectId);
        Delivery delivery =
                jdbc
                        .query(
                                "SELECT * FROM deliveries WHERE project_id = ? AND id = ? FOR UPDATE",
                                DELIVERY,
                                projectId,
                                id)
                        .stream()
                        .findFirst()
                        .orElseThrow(ApiException::notFound);
        if (!delivery.status().equals("DEAD"))
            throw ApiException.conflict(
                    "delivery_not_dead", "Only dead-letter deliveries can be replayed.");
        if (delivery.replayCount() >= 10)
            throw ApiException.conflict(
                    "replay_limit", "A delivery may be replayed up to ten times.");
        projects.reserveCapacity(projectId, 1);
        projects.consumeEventQuota(projectId);
        jdbc.update(
                """
                UPDATE deliveries SET status = 'READY', cycle_attempts = 0, replay_count = replay_count + 1,
                  next_attempt_at = clock_timestamp(), last_error = NULL, last_http_status = NULL, updated_at = clock_timestamp()
                WHERE id = ?
                """,
                id);
        projects.audit(projectId, "delivery.replayed", id);
        return get(projectId, id);
    }
}
