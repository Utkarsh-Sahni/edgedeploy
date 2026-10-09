package com.edgedeploy.entity;

import com.edgedeploy.contracts.DeploymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A single deployment attempt.
 *
 * <p>The api only ever creates deployments in {@link DeploymentStatus#QUEUED}. All later
 * transitions are made by the worker with compare-and-set SQL, so this entity intentionally
 * exposes no status mutators.
 */
@Entity
@Table(name = "deployments")
public class Deployment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Per-project sequence number shown to users ("Deployment #42"). */
    @Column(nullable = false, updatable = false)
    private int number;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeploymentStatus status;

    @Column(name = "image_uri", length = 512)
    private String imageUri;

    @Column(name = "deployment_url", length = 512)
    private String deploymentUrl;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Deployment() {
    }

    private Deployment(Project project, int number, String commitSha) {
        this.project = project;
        this.number = number;
        this.commitSha = commitSha;
        this.status = DeploymentStatus.QUEUED;
    }

    public static Deployment queue(Project project, int number, String commitSha) {
        return new Deployment(project, number, commitSha);
    }

    public int getNumber() {
        return number;
    }

    public Project getProject() {
        return project;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public DeploymentStatus getStatus() {
        return status;
    }

    public String getImageUri() {
        return imageUri;
    }

    public String getDeploymentUrl() {
        return deploymentUrl;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
