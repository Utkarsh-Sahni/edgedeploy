package com.edgedeploy.contracts;

/**
 * Milestones of the deployment timeline. A log line tagged with a step completes it (INFO) or marks
 * it failed (ERROR); untagged lines are ordinary log output. Order is the order shown to users.
 */
public enum DeploymentStep {
    QUEUED,
    CLONE,
    COMMIT,
    FRAMEWORK,
    IMAGE,
    /** Image pushed to the container registry. */
    PUSH,
    /** Deployment target rolled out the new version (ECS service stable). */
    DEPLOY,
    /** The application answered health checks. */
    HEALTH,
    /** Deployment is serving traffic. */
    LIVE
}
