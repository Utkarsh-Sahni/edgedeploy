package com.edgedeploy.worker.github;

/** A git operation failed. Messages are user-facing and never contain credentials. */
public class GitException extends Exception {

    public enum Kind {
        /** Missing, private without credentials, or credentials lack access. */
        REPOSITORY_NOT_ACCESSIBLE,
        /** The requested commit does not exist in the repository (or is not reachable). */
        COMMIT_NOT_FOUND,
        /** HEAD after checkout is not the requested commit. */
        COMMIT_MISMATCH,
        NETWORK,
        TIMEOUT,
        CANCELLED,
        /** git is not installed / not executable on the worker. */
        GIT_UNAVAILABLE,
        FAILED
    }

    private final Kind kind;

    public GitException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
