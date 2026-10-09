package com.edgedeploy.dto;

public record GitHubRepoResponse(
        String fullName,
        String defaultBranch,
        boolean privateRepository,
        String htmlUrl
) {
}
