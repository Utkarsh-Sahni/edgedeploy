package com.edgedeploy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "domains")
public class Domain extends BaseEntity {

    public enum Status {
        PENDING_VERIFICATION,
        ACTIVE,
        FAILED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    @Column(nullable = false, length = 253)
    private String hostname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Domain() {
    }

    public Domain(Project project, String hostname) {
        this.project = project;
        this.hostname = hostname;
        this.status = Status.PENDING_VERIFICATION;
    }

    public Project getProject() {
        return project;
    }

    public String getHostname() {
        return hostname;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
