package com.edgedeploy.dto;

import com.edgedeploy.entity.Framework;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @Schema(example = "my-portfolio")
        @NotBlank @Size(max = 100)
        String name,

        @Schema(example = "octocat/my-portfolio", description = "GitHub repository as owner/name; must be visible to you with write access")
        @NotBlank @Size(max = 200)
        @Pattern(regexp = ValidationPatterns.GITHUB_REPOSITORY, message = "must be a GitHub repository in the form owner/name")
        String repository,

        @Schema(example = "main", description = "Must exist on GitHub")
        @NotBlank @Size(max = 255)
        @Pattern(regexp = ValidationPatterns.GIT_BRANCH, message = "must be a valid git branch name")
        String branch,

        @Schema(description = "Defaults to UNKNOWN; auto-detection arrives with the build pipeline")
        Framework framework,

        @Schema(example = "npm run build")
        @Size(max = 500) @Pattern(regexp = ValidationPatterns.COMMAND, message = "must be a single line")
        String buildCommand,

        @Schema(example = "npm start")
        @Size(max = 500) @Pattern(regexp = ValidationPatterns.COMMAND, message = "must be a single line")
        String startCommand) {
}
