package com.edgedeploy.controller;

import com.edgedeploy.dto.EnvironmentVariableRequest;
import com.edgedeploy.dto.EnvironmentVariableResponse;
import com.edgedeploy.security.AuthenticatedUser;
import com.edgedeploy.service.EnvironmentVariableService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/env")
public class EnvironmentVariableController {

    private final EnvironmentVariableService environmentVariableService;

    public EnvironmentVariableController(EnvironmentVariableService environmentVariableService) {
        this.environmentVariableService = environmentVariableService;
    }

    @GetMapping
    public List<EnvironmentVariableResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId
    ) {
        return environmentVariableService.list(principal.getUserId(), projectId);
    }

    @PutMapping
    public EnvironmentVariableResponse upsert(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody EnvironmentVariableRequest request
    ) {
        return environmentVariableService.upsert(principal.getUserId(), projectId, request);
    }

    @DeleteMapping("/{envId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID envId
    ) {
        environmentVariableService.delete(principal.getUserId(), envId);
    }
}
