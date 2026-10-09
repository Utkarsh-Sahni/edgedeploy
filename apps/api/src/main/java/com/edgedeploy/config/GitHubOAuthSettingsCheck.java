package com.edgedeploy.config;

import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails startup when the GitHub OAuth App credentials are missing. Without this, Spring Boot binds an unset
 * {@code ${GITHUB_CLIENT_ID}} as that literal text and sign-in only fails later, at GitHub, with a 404.
 */
@Component
public class GitHubOAuthSettingsCheck {

    public GitHubOAuthSettingsCheck(OAuth2ClientProperties properties) {
        OAuth2ClientProperties.Registration github = properties.getRegistration().get("github");
        List<String> missing = new ArrayList<>();
        if (github == null || unset(github.getClientId())) {
            missing.add("GITHUB_CLIENT_ID");
        }
        if (github == null || unset(github.getClientSecret())) {
            missing.add("GITHUB_CLIENT_SECRET");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(String.join(" and ", missing) + " not set. Create a GitHub OAuth App "
                    + "(see README, Getting started) and export its credentials before starting the api, "
                    + "e.g. `set -a; source .env; set +a` in this terminal.");
        }
    }

    private static boolean unset(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }
}
