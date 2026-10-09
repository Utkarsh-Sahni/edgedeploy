package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.event.DeploymentRequestedEvent;

/**
 * What the worker does with a deployment request. Exactly one implementation is active, chosen by
 * {@code edgedeploy.mode}: {@link DeploymentAcknowledger} (Phase 2 default: verify and record receipt)
 * or {@link DeploymentOrchestrator} (simulated pipeline).
 */
public interface DeploymentRequestHandler {

    void handle(DeploymentRequestedEvent event);
}
