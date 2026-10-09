package com.edgedeploy.controller;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.dto.UserResponse;
import com.edgedeploy.mapper.EntityMappers;
import com.edgedeploy.security.AuthenticatedUser;
import com.edgedeploy.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final EntityMappers mappers;
    private final EdgeDeployProperties properties;

    public AuthController(UserService userService, EntityMappers mappers, EdgeDeployProperties properties) {
        this.userService = userService;
        this.mappers = mappers;
        this.properties = properties;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return mappers.toUser(userService.getRequired(principal.getUserId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        ResponseCookie cookie = ResponseCookie.from(properties.getJwt().getCookieName(), "")
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }
}
