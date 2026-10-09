package com.edgedeploy.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

/**
 * Subsets of GitHub REST API payloads that EdgeDeploy uses. Unknown fields are ignored so GitHub
 * adding fields never breaks us. These types stay inside the {@code github} package boundary;
 * controllers expose their own DTOs.
 */
public final class GitHubModels {

    private GitHubModels() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, String login, String name, String email, String avatarUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Email(String email, boolean primary, boolean verified) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permissions(boolean admin, boolean push, boolean pull) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            long id,
            String name,
            String fullName,
            Account owner,
            String description,
            @JsonProperty("private") boolean isPrivate,
            String defaultBranch,
            String htmlUrl,
            String language,
            Instant pushedAt,
            Permissions permissions) {

        /** Write access is required to deploy (and, later, to install webhooks). */
        public boolean canDeploy() {
            return permissions != null && (permissions.push() || permissions.admin());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitRef(String sha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Branch(String name, CommitRef commit, @JsonProperty("protected") boolean isProtected) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String sha) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Webhook(long id, boolean active, List<String> events) {
    }

    /** One page of a paginated GitHub list, with whether GitHub advertised a next page. */
    public record Page<T>(List<T> items, int page, int perPage, boolean hasNext) {
    }
}
