package com.edgedeploy.security;

import java.util.UUID;

/**
 * Resolves the user on whose behalf the current request runs. Controllers depend on this
 * abstraction so that swapping the Phase 1 dev user for the authenticated GitHub principal
 * (Phase 2) touches no business code.
 */
public interface CurrentUserProvider {

    UUID currentUserId();
}
