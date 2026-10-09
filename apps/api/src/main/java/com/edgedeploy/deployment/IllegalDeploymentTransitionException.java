package com.edgedeploy.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.exception.ConflictException;

/** The state machine forbids this transition (e.g. FAILED -> RUNNING). Rendered as 409. */
public class IllegalDeploymentTransitionException extends ConflictException {

    public IllegalDeploymentTransitionException(DeploymentStatus from, DeploymentStatus to) {
        super("A deployment cannot move from " + from + " to " + to);
    }
}
