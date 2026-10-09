package com.edgedeploy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Project environment variable. Only the ciphertext is stored; the plaintext never leaves the
 * worker and is never returned by the api. Deliberately has no {@code toString()}.
 */
@Entity
@Table(name = "environment_variables")
public class EnvironmentVariable extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(nullable = false, length = 128)
    private String key;

    @Column(name = "encrypted_value", nullable = false)
    private String encryptedValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnvironmentVariable() {
    }

    public EnvironmentVariable(Project project, String key, String encryptedValue) {
        this.project = project;
        this.key = key;
        this.encryptedValue = encryptedValue;
    }

    public void replaceEncryptedValue(String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }

    public Project getProject() {
        return project;
    }

    public String getKey() {
        return key;
    }

    public String getEncryptedValue() {
        return encryptedValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
