package com.edgedeploy.controller;

import com.edgedeploy.dto.EnvironmentVariableResponse;
import com.edgedeploy.dto.SetEnvironmentVariableRequest;
import com.edgedeploy.security.CurrentUserProvider;
import com.edgedeploy.service.EnvironmentVariableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/env")
@Tag(name = "Environment variables", description = "Runtime environment variables of a project. Values are write-only: "
        + "they are stored encrypted, injected at deploy time (as SSM SecureString parameters on AWS) and never returned. "
        + "Changes take effect on the next deployment.")
public class EnvironmentVariableController {

    private final EnvironmentVariableService variables;
    private final CurrentUserProvider currentUser;

    public EnvironmentVariableController(EnvironmentVariableService variables, CurrentUserProvider currentUser) {
        this.variables = variables;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List variable names", description = "Names and timestamps only; values are never returned.")
    @ApiErrorResponses.NotFound
    public List<EnvironmentVariableResponse> list(@PathVariable UUID projectId) {
        return variables.list(currentUser.currentUserId(), projectId);
    }

    @PutMapping("/{key}")
    @Operation(summary = "Create or replace a variable")
    @ApiResponse(responseCode = "200", description = "Saved; applies to the next deployment")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    @ApiErrorResponses.Conflict
    public EnvironmentVariableResponse set(@PathVariable UUID projectId, @PathVariable String key,
                                           @Valid @RequestBody SetEnvironmentVariableRequest request) {
        return variables.set(currentUser.currentUserId(), projectId, key, request.value());
    }

    @DeleteMapping("/{key}")
    @Operation(summary = "Delete a variable")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiErrorResponses.NotFound
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable String key) {
        variables.delete(currentUser.currentUserId(), projectId, key);
        return ResponseEntity.noContent().build();
    }
}
