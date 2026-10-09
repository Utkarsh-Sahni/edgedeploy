package com.edgedeploy.worker.github;

import java.util.Optional;

/**
 * Supplies repository credentials to {@link GitService}. Credentials are resolved inside the worker and
 * never travel through Kafka events.
 *
 * <p>Today: one configured deploy token ({@link DeployTokenCredentialsProvider}). Later: a GitHub App
 * implementation that mints a short-lived installation token per repository.
 */
public interface GitCredentialsProvider {

    /** @return credentials for {@code owner/name}, or empty to clone anonymously (public repositories) */
    Optional<GitCredentials> credentialsFor(String repository);
}
