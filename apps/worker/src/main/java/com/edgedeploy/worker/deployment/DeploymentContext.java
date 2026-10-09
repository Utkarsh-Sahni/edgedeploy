package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.worker.workspace.Workspace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * State of one pipeline run: what is being built, where, how much time is left, and how far it got.
 */
public final class DeploymentContext {

    private final ClaimedDeployment deployment;
    private final Workspace workspace;
    private final Instant deadline;
    private final Duration deploymentTimeout;
    private final BooleanSupplier cancelled;
    private final Clock clock;

    private DeploymentStatus status = DeploymentStatus.BUILDING;
    private DeploymentStep step = DeploymentStep.CLONE;
    private String commitSha;

    public DeploymentContext(ClaimedDeployment deployment, Workspace workspace, Duration deploymentTimeout,
                             BooleanSupplier cancelled, Clock clock) {
        this.deployment = deployment;
        this.workspace = workspace;
        this.deploymentTimeout = deploymentTimeout;
        this.deadline = clock.instant().plus(deploymentTimeout);
        this.cancelled = cancelled;
        this.clock = clock;
        this.commitSha = deployment.commitSha();
    }

    public UUID deploymentId() {
        return deployment.deploymentId();
    }

    public UUID projectId() {
        return deployment.projectId();
    }

    public ClaimedDeployment deployment() {
        return deployment;
    }

    public Workspace workspace() {
        return workspace;
    }

    public BooleanSupplier cancelled() {
        return cancelled;
    }

    /** The commit being built: requested up front, or resolved from the branch tip at checkout. */
    public String commitSha() {
        return commitSha;
    }

    void commitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public DeploymentStatus status() {
        return status;
    }

    void status(DeploymentStatus status) {
        this.status = status;
    }

    /** The timeline step in progress; a failure is attributed to it. */
    public DeploymentStep step() {
        return step;
    }

    /**
     * Enters a step: fails fast if the deployment was cancelled or is out of time, so no new work starts
     * after either.
     */
    void enter(DeploymentStep next) throws DeploymentFailure, DeploymentCancelledException {
        this.step = next;
        if (cancelled.getAsBoolean()) {
            throw new DeploymentCancelledException();
        }
        if (!clock.instant().isBefore(deadline)) {
            throw timedOut();
        }
    }

    /** A step's own limit, capped by what remains of the overall deployment timeout. */
    Duration timeoutFor(Duration stepLimit) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return remaining.compareTo(stepLimit) < 0 ? remaining : stepLimit;
    }

    boolean pastDeadline() {
        return !clock.instant().isBefore(deadline);
    }

    DeploymentFailure timedOut() {
        return new DeploymentFailure(step, "Deployment exceeded the " + deploymentTimeout.toSeconds() + "s time limit");
    }
}
