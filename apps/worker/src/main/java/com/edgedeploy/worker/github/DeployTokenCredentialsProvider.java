package com.edgedeploy.worker.github;

import com.edgedeploy.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Uses {@code GITHUB_DEPLOY_TOKEN} (a PAT or bot token with read access) for every repository. */
@Component
public class DeployTokenCredentialsProvider implements GitCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(DeployTokenCredentialsProvider.class);
    /** GitHub accepts any username with a token; this is the documented convention. */
    private static final String TOKEN_USERNAME = "x-access-token";

    private final Optional<GitCredentials> credentials;

    public DeployTokenCredentialsProvider(WorkerProperties properties) {
        String token = properties.git().deployToken();
        this.credentials = token == null || token.isBlank()
                ? Optional.empty()
                : Optional.of(new GitCredentials(TOKEN_USERNAME, token.trim()));
        if (credentials.isEmpty()) {
            log.info("GITHUB_DEPLOY_TOKEN not set: only public repositories can be built");
        }
    }

    @Override
    public Optional<GitCredentials> credentialsFor(String repository) {
        return credentials;
    }
}
