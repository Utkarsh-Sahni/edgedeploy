package com.edgedeploy.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The authenticated EdgeDeploy user, stored in the (Redis-backed) HTTP session.
 *
 * <p>Holds identifiers and display data only. The GitHub access token is deliberately <em>not</em>
 * part of the principal, so it never ends up in session storage, logs or serialised security contexts.
 */
public final class EdgeDeployPrincipal implements OAuth2User, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    public static final String ROLE_USER = "ROLE_USER";

    private final UUID userId;
    private final String githubLogin;
    private final String displayName;
    private final String avatarUrl;

    public EdgeDeployPrincipal(UUID userId, String githubLogin, String displayName, String avatarUrl) {
        this.userId = userId;
        this.githubLogin = githubLogin;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    public UUID userId() {
        return userId;
    }

    public String githubLogin() {
        return githubLogin;
    }

    public String displayName() {
        return displayName;
    }

    public String avatarUrl() {
        return avatarUrl;
    }

    /** Spring's principal name; the stable local id rather than the (mutable) GitHub login. */
    @Override
    public String getName() {
        return userId.toString();
    }

    @Override
    public Map<String, Object> getAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("userId", userId.toString());
        attributes.put("login", githubLogin);
        attributes.put("name", displayName);
        if (avatarUrl != null) {
            attributes.put("avatarUrl", avatarUrl);
        }
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(ROLE_USER));
    }

    @Override
    public String toString() {
        return "EdgeDeployPrincipal[userId=" + userId + ", login=" + githubLogin + "]";
    }
}
