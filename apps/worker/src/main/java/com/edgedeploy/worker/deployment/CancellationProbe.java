package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Lets long-running steps notice a user's cancel (the api moves the deployment to STOPPED). The
 * process runner polls it every few seconds and kills the running git/docker process.
 */
@Component
public class CancellationProbe {

    private final DeploymentStore store;

    public CancellationProbe(DeploymentStore store) {
        this.store = store;
    }

    public BooleanSupplier forDeployment(UUID deploymentId) {
        // A deployment that vanished (project deleted) counts as cancelled too.
        return () -> store.currentStatus(deploymentId).map(status -> status == DeploymentStatus.STOPPED).orElse(true);
    }
}
