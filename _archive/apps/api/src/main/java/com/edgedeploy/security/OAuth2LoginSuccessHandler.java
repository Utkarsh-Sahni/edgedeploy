package com.edgedeploy.security;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final JwtService jwtService;
    private final EdgeDeployProperties properties;
    private final OAuth2AuthorizedClientService authorizedClientService;

    public OAuth2LoginSuccessHandler(
            UserService userService,
            JwtService jwtService,
            EdgeDeployProperties properties,
            OAuth2AuthorizedClientService authorizedClientService
    ) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.properties = properties;
        this.authorizedClientService = authorizedClientService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                oauthToken.getAuthorizedClientRegistrationId(),
                oauthToken.getName()
        );
        String accessToken = client != null ? client.getAccessToken().getTokenValue() : null;
        var user = userService.upsertFromGithub(oauthToken.getPrincipal(), accessToken);
        String jwt = jwtService.issueToken(user.getId(), user.getEmail());
        ResponseCookie cookie = ResponseCookie.from(properties.getJwt().getCookieName(), jwt)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.getJwt().getExpiration())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.sendRedirect(properties.getWebOrigin() + "/dashboard");
    }
}
