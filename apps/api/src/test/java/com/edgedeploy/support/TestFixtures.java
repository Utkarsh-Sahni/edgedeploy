package com.edgedeploy.support;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.github.GitHubModels;
import com.edgedeploy.security.EdgeDeployPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared builders for API tests. */
public final class TestFixtures {

    public static final String COMMIT = "3f2a9c1d4e5b6a7980f1e2d3c4b5a69788776655";
    public static final String TEST_KEY = "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLWtleSE=";

    private TestFixtures() {
    }

    public static OAuth2AuthenticationToken authenticationFor(UUID userId) {
        EdgeDeployPrincipal principal = new EdgeDeployPrincipal(userId, "octocat", "The Octocat", null);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
    }

    public static EdgeDeployProperties properties(String apiBaseUrl) {
        return new EdgeDeployProperties(
                List.of("http://localhost:3000"),
                "http://localhost:3000",
                new EdgeDeployProperties.Cookie(null, false),
                new EdgeDeployProperties.Encryption(TEST_KEY),
                new EdgeDeployProperties.GitHub(apiBaseUrl, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new EdgeDeployProperties.Kafka(1, 1, "test"),
                new EdgeDeployProperties.Outbox(Duration.ofSeconds(1), 10, Duration.ofSeconds(1)),
                new EdgeDeployProperties.Sse(Duration.ofMinutes(1)));
    }

    public static GitHubModels.Repository repository(String fullName, boolean push) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new GitHubModels.Repository(4242L, name, fullName, new GitHubModels.Account(owner), "A test repo",
                false, "main", "https://github.com/" + fullName, "TypeScript", Instant.now(),
                new GitHubModels.Permissions(false, push, true));
    }

    public static GitHubModels.Branch branch(String name) {
        return new GitHubModels.Branch(name, new GitHubModels.CommitRef(COMMIT), false);
    }
}
