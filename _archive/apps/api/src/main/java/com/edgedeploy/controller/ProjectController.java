package com.edgedeploy.controller;

import com.edgedeploy.dto.CreateProjectRequest;
import com.edgedeploy.dto.DomainResponse;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.security.AuthenticatedUser;
import com.edgedeploy.service.DomainService;
import com.edgedeploy.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final DomainService domainService;

    public ProjectController(ProjectService projectService, DomainService domainService) {
        this.projectService = projectService;
        this.domainService = domainService;
    }

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return projectService.list(principal.getUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        return projectService.create(principal.getUserId(), request);
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId
    ) {
        return projectService.get(principal.getUserId(), projectId);
    }

    @GetMapping("/{projectId}/domains")
    public List<DomainResponse> domains(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId
    ) {
        return domainService.list(principal.getUserId(), projectId);
    }
}
