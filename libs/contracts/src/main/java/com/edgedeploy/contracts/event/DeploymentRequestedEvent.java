package com.edgedeploy.contracts.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the api (via the transactional outbox) when a deployment is created.
 *
 * @param eventId    unique per event, useful for tracing duplicates
 * @param commitSha  pinned commit, or {@code null} to build the branch tip (resolved by the worker at clone time)
 */
public record DeploymentRequestedEvent(
        UUID eventId,
        UUID deploymentId,
        UUID projectId,
        String repository,
        String branch,
        String commitSha,
        Instant timestamp) {
}
