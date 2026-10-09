package com.edgedeploy.auth;

import com.edgedeploy.entity.User;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubClient;
import com.edgedeploy.github.GitHubModels;
import com.edgedeploy.security.EdgeDeployPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs once per GitHub sign-in, after Spring Security has exchanged the authorization code:
 * loads the GitHub profile (plus primary verified email when the profile hides it), provisions the
 * local user with an encrypted copy of the token, and returns the session principal.
 */
@Component
public class GitHubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuth2UserService.class);

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final GitHubClient gitHub;
    private final UserProvisioningService provisioning;

    @Autowired // the package-private constructor below is for tests
    public GitHubOAuth2UserService(GitHubClient gitHub, UserProvisioningService provisioning) {
        this(new DefaultOAuth2UserService(), gitHub, provisioning);
    }

    GitHubOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate, GitHubClient gitHub,
                            UserProvisioningService provisioning) {
        this.delegate = delegate;
        this.gitHub = gitHub;
        this.provisioning = provisioning;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User gitHubUser = delegate.loadUser(request);
        String token = request.getAccessToken().getTokenValue();

        GitHubProfile profile = toProfile(gitHubUser.getAttributes(), token);
        User user = provisioning.provision(profile, token, request.getAccessToken().getScopes());
        log.info("GitHub sign-in succeeded for {} (user {})", user.getGithubLogin(), user.getId());

        return new EdgeDeployPrincipal(user.getId(), user.getGithubLogin(), user.getName(), user.getAvatarUrl());
    }

    private GitHubProfile toProfile(Map<String, Object> attributes, String token) {
        Object id = attributes.get("id");
        Object login = attributes.get("login");
        if (id == null || login == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info", "GitHub profile is missing id or login", null));
        }
        String email = (String) attributes.get("email");
        if (email == null) {
            email = primaryVerifiedEmail(token);
        }
        return new GitHubProfile(String.valueOf(id), String.valueOf(login), (String) attributes.get("name"), email,
                (String) attributes.get("avatar_url"));
    }

    /** The public profile email is often null; /user/emails (user:email scope) has the real one. */
    private String primaryVerifiedEmail(String token) {
        try {
            return gitHub.getEmails(token).stream()
                    .filter(GitHubModels.Email::primary)
                    .filter(GitHubModels.Email::verified)
                    .map(GitHubModels.Email::email)
                    .findFirst()
                    .orElse(null);
        } catch (GitHubApiException e) {
            // Email is optional; sign-in must not fail because of it.
            log.warn("Could not read GitHub emails ({}); continuing without email", e.kind());
            return null;
        }
    }
}
