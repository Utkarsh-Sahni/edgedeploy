package com.edgedeploy.worker.docker;

/** The project cannot be built as configured (missing script, invalid command). User-facing message. */
public class BuildConfigurationException extends Exception {

    public BuildConfigurationException(String message) {
        super(message);
    }
}
