package com.edgedeploy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EnvironmentVariableRequest(
        @NotBlank
        @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "Key must be an uppercase env var name")
        @Size(max = 128)
        String key,
        @NotBlank @Size(max = 4096) String value
) {
}
