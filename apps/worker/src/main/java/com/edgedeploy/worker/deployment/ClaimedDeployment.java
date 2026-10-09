package com.edgedeploy.worker.deployment;

import java.util.UUID;

/**
 * Snapshot of a deployment and its project's build settings, taken atomically when the worker claims it.
 *
 * @param commitSha           the commit to build; null only for legacy deployments queued without one
 * @param framework           framework chosen in project settings, or {@code UNKNOWN} to auto-detect
 * @param buildCommand        project override for the build command, or null
 * @param startCommand        project override for the start command, or null
 */
public record ClaimedDeployment(
        UUID deploymentId,
        UUID projectId,
        int number,
        String repository,
        String branch,
        String commitSha,
        String framework,
        String buildCommand,
        String startCommand) {
}
