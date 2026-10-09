package com.edgedeploy.contracts;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a deployment. Transitions are explicit: anything not listed in
 * {@link #ALLOWED} is rejected, which keeps the api and worker from drifting
 * into impossible states (e.g. RUNNING -> BUILDING).
 */
public enum DeploymentStatus {
    QUEUED,
    BUILDING,
    /**
     * A container image was built and the pipeline stopped there because no registry/deployment target is
     * configured (local mode). With AWS enabled, BUILDING goes straight to PUSHING.
     */
    IMAGE_BUILT,
    PUSHING,
    DEPLOYING,
    HEALTH_CHECK,
    RUNNING,
    FAILED,
    STOPPED;

    private static final Map<DeploymentStatus, Set<DeploymentStatus>> ALLOWED = new EnumMap<>(DeploymentStatus.class);

    static {
        ALLOWED.put(QUEUED, EnumSet.of(BUILDING, FAILED, STOPPED));
        ALLOWED.put(BUILDING, EnumSet.of(IMAGE_BUILT, PUSHING, FAILED, STOPPED));
        ALLOWED.put(IMAGE_BUILT, EnumSet.of(PUSHING, FAILED, STOPPED));
        ALLOWED.put(PUSHING, EnumSet.of(DEPLOYING, FAILED, STOPPED));
        ALLOWED.put(DEPLOYING, EnumSet.of(HEALTH_CHECK, FAILED, STOPPED));
        ALLOWED.put(HEALTH_CHECK, EnumSet.of(RUNNING, FAILED, STOPPED));
        ALLOWED.put(RUNNING, EnumSet.of(STOPPED));
        ALLOWED.put(FAILED, EnumSet.noneOf(DeploymentStatus.class));
        ALLOWED.put(STOPPED, EnumSet.noneOf(DeploymentStatus.class));
    }

    public boolean canTransitionTo(DeploymentStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<DeploymentStatus> allowedTransitions() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    /** No further transitions are possible. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * The pipeline has finished, successfully or not; nothing changes until a user acts. IMAGE_BUILT counts
     * because it is only ever reached in local mode, where it is the end of the pipeline.
     */
    public boolean isSettled() {
        return this == RUNNING || this == IMAGE_BUILT || isTerminal();
    }

    /**
     * Cancelling is safe only before the deployment target starts rolling out: once ECS is updated, the
     * running application changes and stopping halfway would leave it in an unknown state.
     */
    public boolean isCancellable() {
        return this == QUEUED || this == BUILDING || this == PUSHING;
    }

    /** The worker is actively doing something for this deployment. */
    public boolean isInProgress() {
        return this == BUILDING || this == PUSHING || this == DEPLOYING || this == HEALTH_CHECK;
    }
}
