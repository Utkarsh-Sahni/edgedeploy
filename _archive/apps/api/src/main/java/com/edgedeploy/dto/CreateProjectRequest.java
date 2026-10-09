package com.edgedeploy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String repository,
        @NotBlank @Size(max = 255) String branch,
        @Size(max = 64) String framework
) {
}
