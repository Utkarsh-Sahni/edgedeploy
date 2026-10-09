package com.edgedeploy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param value at most 4 KB (the SSM Parameter Store standard-tier limit used at deploy time)
 */
public record SetEnvironmentVariableRequest(
        @Schema(description = "Write-only; stored encrypted and never returned", example = "https://api.example.com")
        @NotNull @Size(max = 4096)
        String value) {

    @Override
    public String toString() {
        return "SetEnvironmentVariableRequest[value=<redacted>]";
    }
}
