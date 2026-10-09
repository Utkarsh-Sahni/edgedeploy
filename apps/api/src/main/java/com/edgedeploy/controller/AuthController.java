package com.edgedeploy.controller;

import com.edgedeploy.dto.CurrentUserResponse;
import com.edgedeploy.security.CurrentUserProvider;
import com.edgedeploy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session introspection. Sign-in itself is handled by Spring Security:
 * {@code GET /oauth2/authorization/github} starts it, {@code POST /api/auth/logout} ends it.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = """
        Sign in by navigating the browser to `GET /oauth2/authorization/github`. After GitHub approves, the API \
        sets an HttpOnly session cookie (`EDGEDEPLOY_SESSION`) and redirects to the dashboard. \
        Mutating requests must also send the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie. \
        Sign out with `POST /api/auth/logout` (204).""")
public class AuthController {

    private final UserService users;
    private final CurrentUserProvider currentUser;

    public AuthController(UserService users, CurrentUserProvider currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in user", description = "Returns 401 when there is no valid session.")
    public CurrentUserResponse me() {
        return users.get(currentUser.currentUserId());
    }

    @GetMapping("/csrf")
    @SecurityRequirements
    @Operation(summary = "Issue the CSRF cookie",
            description = "Sets the `XSRF-TOKEN` cookie. Echo its value in the `X-XSRF-TOKEN` header on POST/PATCH/DELETE.")
    @ApiResponse(responseCode = "204", description = "Cookie set")
    public ResponseEntity<Void> csrf() {
        // The token cookie is written by SpaCsrfTokenRequestHandler on every response; nothing else to do.
        return ResponseEntity.noContent().build();
    }
}
