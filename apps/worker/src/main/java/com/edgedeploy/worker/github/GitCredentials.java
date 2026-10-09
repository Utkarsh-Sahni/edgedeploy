package com.edgedeploy.worker.github;

/** HTTP credentials for a git remote. {@link #toString()} never reveals the secret. */
public record GitCredentials(String username, String secret) {

    @Override
    public String toString() {
        return "GitCredentials[username=" + username + ", secret=<redacted>]";
    }
}
