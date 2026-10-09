package com.edgedeploy.worker.kafka;

/** A structurally invalid event. Never retried: it goes straight to the dead-letter topic. */
public class InvalidDeploymentEventException extends RuntimeException {

    public InvalidDeploymentEventException(String message) {
        super(message);
    }
}
