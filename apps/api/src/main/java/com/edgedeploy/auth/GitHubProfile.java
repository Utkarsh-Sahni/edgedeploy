package com.edgedeploy.auth;

/**
 * Identity data taken from GitHub at sign-in.
 *
 * @param githubId numeric GitHub user id (immutable, unlike the login)
 * @param email    primary verified email, or null if GitHub exposes none
 */
public record GitHubProfile(String githubId, String login, String name, String email, String avatarUrl) {

    /** GitHub names are optional; fall back to the login for display. */
    public String displayName() {
        return name == null || name.isBlank() ? login : name;
    }
}
