package com.edgedeploy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's GitHub OAuth token, stored only as ciphertext. Accessed exclusively through
 * {@code GitHubTokenStore}; deliberately has no {@code toString()}.
 */
@Entity
@Table(name = "github_credentials")
public class GitHubCredential {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "encrypted_access_token", nullable = false)
    private String encryptedAccessToken;

    @Column(length = 512)
    private String scopes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitHubCredential() {
    }

    public GitHubCredential(UUID userId, String encryptedAccessToken, String scopes) {
        this.userId = userId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.scopes = scopes;
    }

    public void replace(String encryptedAccessToken, String scopes) {
        this.encryptedAccessToken = encryptedAccessToken;
        this.scopes = scopes;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public String getScopes() {
        return scopes;
    }
}
