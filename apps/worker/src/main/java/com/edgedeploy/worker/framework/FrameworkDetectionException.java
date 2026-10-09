package com.edgedeploy.worker.framework;

/** FRAMEWORK_DETECTION_FAILED: the repository is not a project EdgeDeploy can build. User-facing message. */
public class FrameworkDetectionException extends Exception {

    public FrameworkDetectionException(String message) {
        super(message);
    }
}
