package com.edgedeploy.auth;

import com.edgedeploy.entity.User;
import com.edgedeploy.github.GitHubTokenStore;
import com.edgedeploy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Objects;

/**
 * Creates or refreshes the local user for a GitHub identity and stores their GitHub token,
 * atomically. Users are matched on GitHub's immutable numeric id, never on login or email.
 */
@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository users;
    private final GitHubTokenStore tokens;
    private final Clock clock;

    public UserProvisioningService(UserRepository users, GitHubTokenStore tokens, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public User provision(GitHubProfile profile, String accessToken, Collection<String> scopes) {
        String email = claimableEmail(profile);
        User user = users.findByGithubId(profile.githubId())
                .map(existing -> {
                    existing.updateProfile(profile.login(), profile.displayName(), email, profile.avatarUrl());
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Creating user for GitHub account {} ({})", profile.login(), profile.githubId());
                    return new User(profile.githubId(), profile.login(), profile.displayName(), email, profile.avatarUrl());
                });
        user.recordLogin(clock.instant());
        users.saveAndFlush(user);
        tokens.save(user.getId(), accessToken, scopes);
        return user;
    }

    /**
     * Emails are unique locally but can move between GitHub accounts over time. Rather than failing
     * sign-in on a stale clash, the newcomer simply gets no email on record.
     */
    private String claimableEmail(GitHubProfile profile) {
        if (profile.email() == null) {
            return null;
        }
        boolean takenByAnother = users.findByEmail(profile.email())
                .filter(other -> !Objects.equals(other.getGithubId(), profile.githubId()))
                .isPresent();
        if (takenByAnother) {
            log.warn("Email of GitHub account {} already belongs to another user; not storing it", profile.githubId());
            return null;
        }
        return profile.email();
    }
}
