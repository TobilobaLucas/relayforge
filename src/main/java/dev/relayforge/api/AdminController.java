package dev.relayforge.api;

import dev.relayforge.api.ApiModels.CreateProject;
import dev.relayforge.api.ApiModels.Project;
import dev.relayforge.api.ApiModels.ProjectCredential;
import dev.relayforge.project.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/projects")
@Tag(name = "Operator", description = "Operator token required")
public class AdminController {
    private final ProjectService projects;

    public AdminController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping
    @Operation(summary = "Provision a project; its API key is returned only once")
    public ResponseEntity<ProjectCredential> create(@Valid @RequestBody CreateProject request) {
        ProjectCredential result = projects.create(request.name());
        return ResponseEntity.created(URI.create("/api/v1/admin/projects/" + result.project().id()))
                .body(result);
    }

    @GetMapping
    @Operation(summary = "List the 100 newest projects")
    public List<Project> list() {
        return projects.list();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable UUID id) {
        return projects.get(id);
    }

    @PostMapping("/{id}/rotate-key")
    @Operation(summary = "Replace a project key and immediately revoke the previous key")
    public ProjectCredential rotate(@PathVariable UUID id) {
        return projects.rotateKey(id);
    }
}
