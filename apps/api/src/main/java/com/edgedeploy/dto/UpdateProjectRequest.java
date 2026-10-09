package com.edgedeploy.dto;

import com.edgedeploy.entity.Framework;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update: omitted (null) fields are left unchanged. For the commands, an empty string clears
 * the value. The repository cannot be changed; create a new project instead.
 */
public record UpdateProjectRequest(
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String name,

        @Schema(description = "Must exist on GitHub")
        @Size(min = 1, max = 255)
        @Pattern(regexp = ValidationPatterns.GIT_BRANCH, message = "must be a valid git branch name")
        String branch,

        Framework framework,

        @Size(max = 500) @Pattern(regexp = ValidationPatterns.COMMAND, message = "must be a single line")
        String buildCommand,

        @Size(max = 500) @Pattern(regexp = ValidationPatterns.COMMAND, message = "must be a single line")
        String startCommand) {
}
