package com.edgedeploy.security;

import com.edgedeploy.auth.DiscardingAuthorizedClientRepository;
import com.edgedeploy.auth.GitHubOAuth2UserService;
import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.config.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Authentication model: GitHub OAuth2 login -> server-side session (Spring Session in Redis) carried
 * by an HttpOnly, SameSite=Lax cookie. State-changing requests additionally need the CSRF header.
 *
 * <ul>
 *   <li>{@code /oauth2/authorization/github}: starts sign-in (Spring Security filter)</li>
 *   <li>{@code /login/oauth2/code/github}: GitHub callback; redirects to the dashboard</li>
 *   <li>{@code /api/**}: requires a session; 401/403 as ProblemDetail, never redirects</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final RequestMatcher API = request -> request.getRequestURI().startsWith(request.getContextPath() + "/api/");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, EdgeDeployProperties properties,
                                            GitHubOAuth2UserService gitHubUserService, ObjectMapper objectMapper)
            throws Exception {
        ProblemDetailSecurityHandler problems = new ProblemDetailSecurityHandler(objectMapper);
        String webUrl = properties.webUrl().replaceAll("/+$", "");

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository(properties.cookie()))
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/auth/csrf", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth -> oauth
                        .loginPage(webUrl + "/login")
                        .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                        .userInfoEndpoint(userInfo -> userInfo.userService(gitHubUserService))
                        .successHandler(loginSuccessHandler(webUrl))
                        .failureHandler(loginFailureHandler(webUrl)))
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(problems, API)
                        .accessDeniedHandler(problems))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true));
        return http.build();
    }

    private static SimpleUrlAuthenticationSuccessHandler loginSuccessHandler(String webUrl) {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler(webUrl + "/dashboard");
        // Fixed target: never honour a caller-supplied redirect (open-redirect protection).
        handler.setAlwaysUseDefaultTargetUrl(true);
        return handler;
    }

    private static AuthenticationFailureHandler loginFailureHandler(String webUrl) {
        SimpleUrlAuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler(webUrl + "/login?error=oauth");
        return (request, response, exception) -> {
            log.warn("GitHub sign-in failed: {}", exception.getMessage());
            delegate.onAuthenticationFailure(request, response, exception);
        };
    }

    private static CookieCsrfTokenRepository csrfTokenRepository(EdgeDeployProperties.Cookie cookie) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setHeaderName(CSRF_HEADER);
        repository.setCookieCustomizer(builder -> {
            builder.path("/").secure(cookie.secure()).sameSite("Lax");
            if (StringUtils.hasText(cookie.domain())) {
                builder.domain(cookie.domain());
            }
        });
        return repository;
    }

    private static CorsConfigurationSource corsConfigurationSource(EdgeDeployProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.webOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, CSRF_HEADER,
                RequestIdFilter.HEADER, "Last-Event-ID"));
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, RequestIdFilter.HEADER));
        // Session cookie must accompany cross-origin calls from the dashboard; origins are explicit above.
        cors.setAllowCredentials(true);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
