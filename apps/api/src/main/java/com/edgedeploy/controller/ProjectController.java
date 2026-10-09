package com.edgedeploy.controller;

import com.edgedeploy.dto.CreateProjectRequest;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.dto.UpdateProjectRequest;
import com.edgedeploy.security.CurrentUserProvider;
import com.edgedeploy.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Projects belong to the signed-in user; other users' projects always answer 404.")
public class ProjectController {

    private final ProjectService projects;
    private final CurrentUserProvider currentUser;

    public ProjectController(ProjectService projects, CurrentUserProvider currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Create a project from a GitHub repository",
            description = "Verifies on GitHub that the repository is visible to you with write access and that the branch exists.")
    @ApiResponse(responseCode = "201", description = "Created; `Location` points at the project")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.Conflict
    @ApiErrorResponses.GitHubBacked
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse project = projects.create(currentUser.currentUserId(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(project.id()).toUri();
        return ResponseEntity.created(location).body(project);
    }

    @GetMapping
    @Operation(summary = "List your projects", description = "Newest first.")
    public List<ProjectResponse> list() {
        return projects.list(currentUser.currentUserId());
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Get a project")
    @ApiErrorResponses.NotFound
    public ProjectResponse get(@PathVariable UUID projectId) {
        return projects.get(currentUser.currentUserId(), projectId);
    }

    @PatchMapping("/{projectId}")
    @Operation(summary = "Update a project",
            description = "Omitted fields are unchanged; an empty command clears it. A new branch is verified on GitHub.")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    @ApiErrorResponses.GitHubBacked
    public ProjectResponse update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProjectRequest request) {
        return projects.update(currentUser.currentUserId(), projectId, request);
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "Delete a project", description = "Also deletes its deployments and logs.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiErrorResponses.NotFound
    public ResponseEntity<Void> delete(@PathVariable UUID projectId) {
        projects.delete(currentUser.currentUserId(), projectId);
        return ResponseEntity.noContent().build();
    }
}
