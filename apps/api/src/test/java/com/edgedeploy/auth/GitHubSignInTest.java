package com.edgedeploy.auth;

import com.edgedeploy.entity.User;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubClient;
import com.edgedeploy.github.GitHubModels;
import com.edgedeploy.github.GitHubTokenStore;
import com.edgedeploy.repository.UserRepository;
import com.edgedeploy.security.EdgeDeployPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GitHub sign-in: OAuth user loading + local user provisioning. The HTTP redirect dance itself is
 * Spring Security's; this covers everything EdgeDeploy adds on top of it.
 */
@ExtendWith(MockitoExtension.class)
class GitHubSignInTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final String TOKEN = "gho_live_token";

    @Mock
    OAuth2UserService<OAuth2UserRequest, OAuth2User> gitHubUserInfo;
    @Mock
    GitHubClient gitHubClient;
    @Mock
    UserRepository users;
    @Mock
    GitHubTokenStore tokens;

    private GitHubOAuth2UserService service;

    @BeforeEach
    void setUp() {
        UserProvisioningService provisioning = new UserProvisioningService(users, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new GitHubOAuth2UserService(gitHubUserInfo, gitHubClient, provisioning);
        org.mockito.Mockito.lenient().when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void firstSignInCreatesTheUserAndStoresTheTokenThroughTheTokenStore() {
        givenGitHubProfile(Map.of("id", 583231, "login", "octocat", "name", "The Octocat",
                "email", "octocat@github.com", "avatar_url", "https://avatars/1"));
        when(users.findByGithubId("583231")).thenReturn(Optional.empty());
        when(users.findByEmail("octocat@github.com")).thenReturn(Optional.empty());

        OAuth2User result = service.loadUser(request());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertThat(user.getGithubId()).isEqualTo("583231");
        assertThat(user.getGithubLogin()).isEqualTo("octocat");
        assertThat(user.getEmail()).isEqualTo("octocat@github.com");
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
        verify(tokens).save(eq(user.getId()), eq(TOKEN), eq(Set.of("read:user", "user:email", "repo")));

        assertThat(result).isInstanceOf(EdgeDeployPrincipal.class);
        EdgeDeployPrincipal principal = (EdgeDeployPrincipal) result;
        assertThat(principal.userId()).isEqualTo(user.getId());
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
        // The token must never travel inside the principal (it is stored in the HTTP session).
        assertThat(principal.getAttributes().values()).doesNotContain(TOKEN);
        assertThat(principal.toString()).doesNotContain(TOKEN);
    }

    @Test
    void returningUserIsMatchedByGitHubIdAndRefreshed() {
        User existing = new User("583231", "old-login", "Old Name", "octocat@github.com", null);
        givenGitHubProfile(Map.of("id", 583231, "login", "octocat", "name", "The Octocat",
                "email", "octocat@github.com", "avatar_url", "https://avatars/2"));
        when(users.findByGithubId("583231")).thenReturn(Optional.of(existing));
        when(users.findByEmail("octocat@github.com")).thenReturn(Optional.of(existing));

        EdgeDeployPrincipal principal = (EdgeDeployPrincipal) service.loadUser(request());

        assertThat(principal.userId()).isEqualTo(existing.getId());
        assertThat(existing.getGithubLogin()).isEqualTo("octocat");
        assertThat(existing.getName()).isEqualTo("The Octocat");
        assertThat(existing.getAvatarUrl()).isEqualTo("https://avatars/2");
        assertThat(existing.getLastLoginAt()).isEqualTo(NOW);
        verify(tokens).save(eq(existing.getId()), eq(TOKEN), any());
    }

    @Test
    void privateEmailIsReadFromTheEmailsEndpoint() {
        Map<String, Object> attributes = new HashMap<>(Map.of("id", 7, "login", "ghost"));
        givenGitHubProfile(attributes);
        when(gitHubClient.getEmails(TOKEN)).thenReturn(List.of(
                new GitHubModels.Email("unverified@example.com", true, false),
                new GitHubModels.Email("ghost@example.com", true, true)));
        when(users.findByGithubId("7")).thenReturn(Optional.empty());
        when(users.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        service.loadUser(request());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ghost@example.com");
        assertThat(saved.getValue().getName()).isEqualTo("ghost"); // falls back to login
    }

    @Test
    void signInSucceedsWithoutEmailWhenGitHubWontShareIt() {
        givenGitHubProfile(new HashMap<>(Map.of("id", 8, "login", "quiet")));
        when(gitHubClient.getEmails(TOKEN)).thenThrow(new GitHubApiException(GitHubApiException.Kind.OTHER, 403, "scope"));
        when(users.findByGithubId("8")).thenReturn(Optional.empty());

        assertThat(service.loadUser(request())).isInstanceOf(EdgeDeployPrincipal.class);
        verify(users, never()).findByEmail(anyString());
    }

    @Test
    void emailHeldByAnotherAccountIsNotStolen() {
        User other = new User("999", "someone", "Someone", "shared@example.com", null);
        givenGitHubProfile(Map.of("id", 10, "login", "newbie", "email", "shared@example.com"));
        when(users.findByGithubId("10")).thenReturn(Optional.empty());
        when(users.findByEmail("shared@example.com")).thenReturn(Optional.of(other));

        service.loadUser(request());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
    }

    private void givenGitHubProfile(Map<String, Object> attributes) {
        when(gitHubUserInfo.loadUser(any())).thenReturn(new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id"));
    }

    private static OAuth2UserRequest request() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("github")
                .clientId("id").clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, TOKEN, NOW, null,
                Set.of("read:user", "user:email", "repo"));
        return new OAuth2UserRequest(registration, token);
    }
}
