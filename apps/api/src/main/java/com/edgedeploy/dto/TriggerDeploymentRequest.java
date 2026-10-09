package com.edgedeploy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * @param commitSha commit to deploy (7–40 hex chars, resolved to the full SHA via GitHub), or
 *                  {@code null} to deploy the current tip of the project's branch
 */
public record TriggerDeploymentRequest(
        @Schema(example = "3f2a9c1", nullable = true)
        @Pattern(regexp = "^[0-9a-f]{7,40}$", message = "must be a 7 to 40 character lowercase commit SHA")
        String commitSha) {

    public static TriggerDeploymentRequest branchTip() {
        return new TriggerDeploymentRequest(null);
    }
}
