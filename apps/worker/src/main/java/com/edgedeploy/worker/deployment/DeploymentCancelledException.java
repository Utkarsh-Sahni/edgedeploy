package com.edgedeploy.worker.deployment;

/** The user cancelled the deployment (it is STOPPED in the database); stop working on it. */
public class DeploymentCancelledException extends Exception {

    public DeploymentCancelledException() {
        super("Deployment cancelled");
    }
}
