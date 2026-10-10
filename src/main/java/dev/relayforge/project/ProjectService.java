package dev.relayforge.project;

import dev.relayforge.api.ApiModels.Project;
import dev.relayforge.api.ApiModels.ProjectCredential;
import dev.relayforge.common.ApiException;
import dev.relayforge.config.RelayProperties;
import dev.relayforge.security.Credentials;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private static final RowMapper<Project> PROJECT =
            (rs, n) ->
                    new Project(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbc;
    private final RelayProperties properties;

    public ProjectService(JdbcTemplate jdbc, RelayProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public ProjectCredential create(String name) {
        UUID id = UUID.randomUUID();
        String key = Credentials.token("rf_live_");
        jdbc.update(
                "INSERT INTO projects(id, name, api_key_hash) VALUES (?, ?, ?)",
                id,
                name.trim(),
                Credentials.hash(key));
        audit(id, "project.created", id);
        return new ProjectCredential(get(id), key);
    }

    public Project get(UUID id) {
        return jdbc
                .query("SELECT id, name, created_at FROM projects WHERE id = ?", PROJECT, id)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    public List<Project> list() {
        return jdbc.query(
                "SELECT id, name, created_at FROM projects ORDER BY created_at DESC LIMIT 100",
                PROJECT);
    }

    @Transactional
    public ProjectCredential rotateKey(UUID id) {
        lock(id);
        String key = Credentials.token("rf_live_");
        jdbc.update("UPDATE projects SET api_key_hash = ? WHERE id = ?", Credentials.hash(key), id);
        audit(id, "project.key_rotated", id);
        return new ProjectCredential(get(id), key);
    }

    // All operations that grow a project's queue take this lock first.
    // The transaction boundary belongs to the caller, so quota checks and inserts stay atomic.
    public void lock(UUID id) {
        if (jdbc.query(
                        "SELECT id FROM projects WHERE id = ? FOR UPDATE",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        id)
                .isEmpty()) {
            throw ApiException.notFound();
        }
    }

    public void reserveCapacity(UUID projectId, int additional) {
        long pending =
                jdbc.queryForObject(
                        "SELECT count(*) FROM deliveries WHERE project_id = ? AND status IN ('READY', 'IN_FLIGHT')",
                        Long.class,
                        projectId);
        if (pending + additional > properties.maxPendingPerProject()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "queue_full",
                    "The project has reached its pending delivery limit. Allow the queue to drain before submitting more work.");
        }
    }

    public void consumeEventQuota(UUID projectId) {
        int changed =
                jdbc.update(
                        """
                UPDATE projects SET
                  rate_count = CASE WHEN rate_window >= date_trunc('minute', statement_timestamp()) THEN rate_count + 1 ELSE 1 END,
                  rate_window = date_trunc('minute', statement_timestamp())
                WHERE id = ? AND (rate_window < date_trunc('minute', statement_timestamp()) OR rate_count < ?)
                """,
                        projectId,
                        properties.eventsPerMinute());
        if (changed == 0)
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "event_rate_exceeded",
                    "The project has reached its event limit for this minute.");
    }

    public void audit(UUID projectId, String action, UUID resourceId) {
        jdbc.update(
                "INSERT INTO audit_log(project_id, action, resource_id) VALUES (?, ?, ?)",
                projectId,
                action,
                resourceId);
    }
}
