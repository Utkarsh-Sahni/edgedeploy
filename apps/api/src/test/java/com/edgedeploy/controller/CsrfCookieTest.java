package com.edgedeploy.controller;

import com.edgedeploy.auth.GitHubOAuth2UserService;
import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.security.SecurityConfig;
import com.edgedeploy.security.SecurityContextCurrentUserProvider;
import com.edgedeploy.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real CSRF cookie, in a context of its own: Spring Security's {@code csrf()} test helper (used by
 * ApiWebLayerTest) swaps the token repository of the shared filter, which would hide the cookie.
 */
@WebMvcTest(controllers = AuthController.class, properties = "edgedeploy.cookie.domain=")
@Import({SecurityConfig.class, SecurityContextCurrentUserProvider.class})
@EnableConfigurationProperties(EdgeDeployProperties.class)
@ActiveProfiles("test")
class CsrfCookieTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UserService userService;
    @MockitoBean
    GitHubOAuth2UserService gitHubOAuth2UserService;

    @Test
    void csrfEndpointIsPublicAndIssuesAReadableLaxCookie() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(cookie().sameSite("XSRF-TOKEN", "Lax"))
                .andExpect(cookie().path("XSRF-TOKEN", "/"));
    }
}
