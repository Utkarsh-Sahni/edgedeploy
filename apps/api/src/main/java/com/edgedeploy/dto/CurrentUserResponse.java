package com.edgedeploy.dto;

import java.util.UUID;

/** The signed-in user. Never includes GitHub tokens or other credentials. */
public record CurrentUserResponse(UUID id, String login, String name, String email, String avatarUrl) {
}
