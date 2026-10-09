package com.edgedeploy.dto;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String name,
        String githubId,
        String githubLogin
) {
}
