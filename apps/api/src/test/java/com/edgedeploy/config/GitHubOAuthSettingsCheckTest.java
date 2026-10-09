package com.edgedeploy.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubOAuthSettingsCheckTest {

    @Test
    void acceptsConfiguredCredentials() {
        assertThatCode(() -> new GitHubOAuthSettingsCheck(properties("abc123", "secret"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnresolvedPlaceholdersAndBlanks() {
        assertThatThrownBy(() -> new GitHubOAuthSettingsCheck(properties("${GITHUB_CLIENT_ID}", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET not set");
    }

    @Test
    void rejectsAMissingRegistration() {
        assertThatThrownBy(() -> new GitHubOAuthSettingsCheck(new OAuth2ClientProperties()))
                .hasMessageContaining("GITHUB_CLIENT_ID");
    }

    private static OAuth2ClientProperties properties(String clientId, String clientSecret) {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        OAuth2ClientProperties.Registration github = new OAuth2ClientProperties.Registration();
        github.setClientId(clientId);
        github.setClientSecret(clientSecret);
        properties.getRegistration().put("github", github);
        return properties;
    }
}
