package com.edgedeploy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** A person signed in through GitHub. The GitHub token lives in {@link GitHubCredential}, never here. */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    /** Primary verified email; null when the user keeps all GitHub emails private. */
    @Column(length = 320)
    private String email;

    @Column(nullable = false)
    private String name;

    /** GitHub's immutable numeric user id (as text). Logins can change; this cannot. */
    @Column(name = "github_id", nullable = false, length = 64, updatable = false)
    private String githubId;

    @Column(name = "github_login", nullable = false, length = 39)
    private String githubLogin;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String githubId, String githubLogin, String name, String email, String avatarUrl) {
        this.githubId = githubId;
        updateProfile(githubLogin, name, email, avatarUrl);
    }

    /** Refreshes profile data from GitHub on every sign-in (logins, names and avatars change). */
    public void updateProfile(String githubLogin, String name, String email, String avatarUrl) {
        this.githubLogin = githubLogin;
        this.name = name;
        this.email = email;
        this.avatarUrl = avatarUrl;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getGithubId() {
        return githubId;
    }

    public String getGithubLogin() {
        return githubLogin;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
