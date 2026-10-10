package dev.relayforge.endpoint;

import dev.relayforge.api.ApiModels.CreateEndpoint;
import dev.relayforge.api.ApiModels.Endpoint;
import dev.relayforge.api.ApiModels.EndpointCredential;
import dev.relayforge.common.ApiException;
import dev.relayforge.common.JsonCodec;
import dev.relayforge.project.ProjectService;
import dev.relayforge.security.Credentials;
import dev.relayforge.security.SecretVault;
import dev.relayforge.security.UrlPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointService {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final SecretVault vault;
    private final UrlPolicy urls;
    private final JsonCodec json;
    private final RowMapper<Endpoint> rowMapper;

    public EndpointService(
            JdbcTemplate jdbc,
            ProjectService projects,
            SecretVault vault,
            UrlPolicy urls,
            JsonCodec json) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.vault = vault;
        this.urls = urls;
        this.json = json;
        this.rowMapper =
                (rs, n) -> {
                    Set<String> types = new HashSet<>();
                    json.read(rs.getString("event_types"))
                            .forEach(type -> types.add(type.asText()));
                    return new Endpoint(
                            rs.getObject("id", UUID.class),
                            rs.getString("url"),
                            rs.getString("description"),
                            Set.copyOf(types),
                            rs.getBoolean("enabled"),
                            rs.getTimestamp("created_at").toInstant());
                };
    }

    @Transactional
    public EndpointCredential create(UUID projectId, CreateEndpoint request) {
        urls.validate(request.url());
        projects.lock(projectId);
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM endpoints WHERE project_id = ?",
                        Long.class,
                        projectId)
                >= 100) {
            throw ApiException.conflict(
                    "endpoint_limit", "A project may have up to 100 endpoints.");
        }
        UUID id = UUID.randomUUID();
        String secret = Credentials.token("whsec_");
        jdbc.update(
                "INSERT INTO endpoints(id, project_id, url, description, event_types, encrypted_secret) VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                id,
                projectId,
                request.url(),
                request.description().trim(),
                json.write(request.eventTypes()),
                vault.encrypt(secret, id));
        projects.audit(projectId, "endpoint.created", id);
        return new EndpointCredential(get(projectId, id), secret);
    }

    public Endpoint get(UUID projectId, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM endpoints WHERE project_id = ? AND id = ?",
                        rowMapper,
                        projectId,
                        id)
                .stream()
                .findFirst()
                .orElseThrow(ApiException::notFound);
    }

    public List<Endpoint> list(UUID projectId) {
        return jdbc.query(
                "SELECT * FROM endpoints WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT 100",
                rowMapper,
                projectId);
    }

    @Transactional
    public Endpoint update(UUID projectId, UUID id, boolean enabled) {
        projects.lock(projectId);
        if (jdbc.update(
                        "UPDATE endpoints SET enabled = ? WHERE project_id = ? AND id = ?",
                        enabled,
                        projectId,
                        id)
                == 0) {
            throw ApiException.notFound();
        }
        projects.audit(projectId, enabled ? "endpoint.resumed" : "endpoint.paused", id);
        return get(projectId, id);
    }
}
