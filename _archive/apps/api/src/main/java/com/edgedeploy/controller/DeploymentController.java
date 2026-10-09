package com.edgedeploy.controller;

import com.edgedeploy.dto.CreateDeploymentRequest;
import com.edgedeploy.dto.DeploymentLogResponse;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.security.AuthenticatedUser;
import com.edgedeploy.service.DeploymentService;
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
@RequestMapping("/api/v1")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @GetMapping("/projects/{projectId}/deployments")
    public List<DeploymentResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId
    ) {
        return deploymentService.list(principal.getUserId(), projectId);
    }

    @PostMapping("/projects/{projectId}/deployments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeploymentResponse create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody(required = false) CreateDeploymentRequest request
    ) {
        return deploymentService.create(
                principal.getUserId(),
                projectId,
                request == null ? new CreateDeploymentRequest(null) : request
        );
    }

    @GetMapping("/deployments/{deploymentId}")
    public DeploymentResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID deploymentId
    ) {
        return deploymentService.get(principal.getUserId(), deploymentId);
    }

    @GetMapping("/deployments/{deploymentId}/logs")
    public List<DeploymentLogResponse> logs(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID deploymentId
    ) {
        return deploymentService.logs(principal.getUserId(), deploymentId);
    }

    @PostMapping("/deployments/{deploymentId}/stop")
    public DeploymentResponse stop(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID deploymentId
    ) {
        return deploymentService.stop(principal.getUserId(), deploymentId);
    }
}
