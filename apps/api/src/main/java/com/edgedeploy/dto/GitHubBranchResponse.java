package com.edgedeploy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubBranchResponse(String name, String commitSha, @JsonProperty("protected") boolean isProtected) {
}
