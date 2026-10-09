package com.edgedeploy.github;

/** A failed call to GitHub, classified so the api can answer with the right status code. */
public class GitHubApiException extends RuntimeException {

    public enum Kind {
        /** Token missing, expired or revoked: the user must sign in again. */
        UNAUTHORIZED,
        /** Resource absent, or private and not visible to this user (GitHub does not distinguish). */
        NOT_FOUND,
        RATE_LIMITED,
        /** Network failure, timeout or 5xx from GitHub. */
        UNAVAILABLE,
        OTHER
    }

    private final Kind kind;
    private final int upstreamStatus;

    public GitHubApiException(Kind kind, int upstreamStatus, String message) {
        super(message);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
    }

    public GitHubApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.upstreamStatus = 0;
    }

    public Kind kind() {
        return kind;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
