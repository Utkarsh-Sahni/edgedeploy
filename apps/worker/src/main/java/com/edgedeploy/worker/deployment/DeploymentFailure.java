package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStep;

/**
 * An expected way for a deployment to fail (bad repository, unknown commit, failing build...). The
 * message is shown to the user, so it must be specific and must never contain secrets.
 */
public class DeploymentFailure extends Exception {

    private final DeploymentStep step;

    public DeploymentFailure(DeploymentStep step, String userMessage) {
        super(userMessage);
        this.step = step;
    }

    public DeploymentStep step() {
        return step;
    }
}
