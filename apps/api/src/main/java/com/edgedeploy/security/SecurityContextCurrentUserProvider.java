package com.edgedeploy.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the current user from the authenticated principal. User ids supplied by clients are
 * never trusted: this is the only source of "who is calling".
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID currentUserId() {
        return currentPrincipal().userId();
    }

    public EdgeDeployPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof EdgeDeployPrincipal principal) {
            return principal;
        }
        throw new AuthenticationCredentialsNotFoundException("Not signed in");
    }
}
