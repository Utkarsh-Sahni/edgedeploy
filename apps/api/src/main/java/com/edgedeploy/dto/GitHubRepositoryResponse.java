package com.edgedeploy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record GitHubRepositoryResponse(
        long id,
        String name,
        @Schema(example = "octocat/portfolio") String fullName,
        String owner,
        String description,
        String defaultBranch,
        @JsonProperty("private") boolean isPrivate,
        String htmlUrl,
        String language,
        Instant pushedAt,
        @Schema(description = "Whether the user has write access, which EdgeDeploy requires to create a project")
        boolean canDeploy) {
}
