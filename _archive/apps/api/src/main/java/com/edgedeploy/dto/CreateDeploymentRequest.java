package com.edgedeploy.dto;

import jakarta.validation.constraints.Size;

public record CreateDeploymentRequest(
        @Size(max = 64) String commitSha
) {
}
