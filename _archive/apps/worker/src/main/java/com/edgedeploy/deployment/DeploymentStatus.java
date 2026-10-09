package com.edgedeploy.deployment;

public enum DeploymentStatus {
    QUEUED,
    BUILDING,
    PUSHING,
    DEPLOYING,
    HEALTH_CHECK,
    RUNNING,
    FAILED,
    STOPPED
}
