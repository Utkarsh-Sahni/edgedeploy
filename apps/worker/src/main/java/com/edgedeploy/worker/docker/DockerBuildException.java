package com.edgedeploy.worker.docker;

/** {@code docker build} did not produce an image. Messages are user-facing and secret-free. */
public class DockerBuildException extends Exception {

    public enum Kind {
        DAEMON_UNAVAILABLE,
        /** The registry refused our credentials (expired token, missing IAM permission). */
        REGISTRY_AUTH,
        BUILD_FAILED,
        TIMEOUT,
        CANCELLED
    }

    private final Kind kind;

    public DockerBuildException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
