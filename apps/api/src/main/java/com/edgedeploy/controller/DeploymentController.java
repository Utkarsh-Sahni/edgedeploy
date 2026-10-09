package com.edgedeploy.controller;

import com.edgedeploy.deployment.DeploymentEventBroadcaster;
import com.edgedeploy.deployment.DeploymentLogStreamBroadcaster;
import com.edgedeploy.dto.DeploymentLogResponse;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.dto.TriggerDeploymentRequest;
import com.edgedeploy.security.CurrentUserProvider;
import com.edgedeploy.service.DeploymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Deployments", description = "Deployments run asynchronously: creation returns 202 with status QUEUED.")
public class DeploymentController {

    private final DeploymentService deployments;
    private final DeploymentEventBroadcaster broadcaster;
    private final DeploymentLogStreamBroadcaster logStreams;
    private final CurrentUserProvider currentUser;

    public DeploymentController(DeploymentService deployments, DeploymentEventBroadcaster broadcaster,
                                DeploymentLogStreamBroadcaster logStreams, CurrentUserProvider currentUser) {
        this.deployments = deployments;
        this.broadcaster = broadcaster;
        this.logStreams = logStreams;
        this.currentUser = currentUser;
    }

    @PostMapping("/projects/{projectId}/deployments")
    @Operation(summary = "Deploy a project",
            description = "Resolves the commit on GitHub (the given SHA, or the branch tip), records a QUEUED deployment "
                    + "and publishes a DeploymentRequested event. Body is optional.")
    @ApiResponse(responseCode = "202", description = "Queued; `Location` points at the deployment")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    @ApiErrorResponses.Conflict
    @ApiErrorResponses.GitHubBacked
    public ResponseEntity<DeploymentResponse> trigger(
            @PathVariable UUID projectId,
            @Valid @RequestBody(required = false) TriggerDeploymentRequest request) {
        DeploymentResponse deployment = deployments.trigger(currentUser.currentUserId(), projectId,
                request != null ? request : TriggerDeploymentRequest.branchTip());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/deployments/{id}").buildAndExpand(deployment.id()).toUri();
        return ResponseEntity.accepted().location(location).body(deployment);
    }

    @GetMapping("/projects/{projectId}/deployments")
    @Operation(summary = "List a project's deployments", description = "Newest first.")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    public List<DeploymentResponse> listForProject(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return deployments.listForProject(currentUser.currentUserId(), projectId, limit);
    }

    @GetMapping("/deployments")
    @Operation(summary = "Your recent deployments across all projects", description = "Newest first.")
    @ApiErrorResponses.BadRequest
    public List<DeploymentResponse> recent(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return deployments.recent(currentUser.currentUserId(), limit);
    }

    @GetMapping("/deployments/{deploymentId}")
    @Operation(summary = "Get a deployment")
    @ApiErrorResponses.NotFound
    public DeploymentResponse get(@PathVariable UUID deploymentId) {
        return deployments.get(currentUser.currentUserId(), deploymentId);
    }

    @PostMapping("/deployments/{deploymentId}/cancel")
    @Operation(summary = "Cancel a deployment", description = "Allowed while QUEUED or in progress; moves it to STOPPED.")
    @ApiErrorResponses.NotFound
    @ApiErrorResponses.Conflict
    public DeploymentResponse cancel(@PathVariable UUID deploymentId) {
        return deployments.cancel(currentUser.currentUserId(), deploymentId);
    }

    @GetMapping("/deployments/{deploymentId}/logs")
    @Operation(summary = "Deployment log lines",
            description = "In order. Lines tagged with a `step` are timeline milestones. Use `after` (a `seq`) to page or resume.")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    public List<DeploymentLogResponse> logs(
            @PathVariable UUID deploymentId,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(5000) int limit) {
        return deployments.logs(currentUser.currentUserId(), deploymentId, after, limit);
    }

    /**
     * Live logs. Sends missed lines first, then new ones as the worker writes them; closes once the
     * deployment settles. SSE event ids are line {@code seq} values, so reconnecting browsers resume
     * automatically via {@code Last-Event-ID}.
     */
    @GetMapping(path = "/deployments/{deploymentId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live deployment logs (Server-Sent Events)",
            description = "Emits `log` events ({seq, level, step, message, timestamp}). Resume with the `Last-Event-ID` "
                    + "header or the `after` parameter. Closes when the deployment settles.")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    public SseEmitter logStream(
            @PathVariable UUID deploymentId,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        DeploymentResponse deployment = deployments.get(currentUser.currentUserId(), deploymentId); // authorises
        long resumeAfter = Math.max(after, parseSeq(lastEventId));
        return logStreams.subscribe(deploymentId, resumeAfter, deployment.status().isSettled());
    }

    private static long parseSeq(String lastEventId) {
        try {
            return lastEventId == null ? 0 : Math.max(0, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Live status stream. Subscribes first, then sends a database snapshot, so no transition that
     * happens in between can be missed. The stream closes once the deployment settles.
     */
    @GetMapping(path = "/deployments/{deploymentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live status (Server-Sent Events)",
            description = "Emits `deployment` events (DeploymentStatusEvent JSON); re-read the deployment on each one. "
                    + "Closes when the deployment settles.")
    @ApiErrorResponses.NotFound
    public SseEmitter events(@PathVariable UUID deploymentId) {
        UUID userId = currentUser.currentUserId();
        deployments.get(userId, deploymentId); // authorise before allocating an emitter
        SseEmitter emitter = broadcaster.subscribe(deploymentId);
        broadcaster.send(emitter, deployments.snapshot(userId, deploymentId));
        return emitter;
    }
}
