package com.edgedeploy.github;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a user's GitHub OAuth token lives. The only implementation today encrypts it into Postgres;
 * swapping in AWS Secrets Manager or Vault later means a new implementation, not new call sites.
 */
public interface GitHubTokenStore {

    void save(UUID userId, String accessToken, Collection<String> scopes);

    Optional<String> findAccessToken(UUID userId);

    void delete(UUID userId);
}
