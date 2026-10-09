package com.edgedeploy.dto;

public record GitHubBranchResponse(
        String name,
        String commitSha
) {
}
