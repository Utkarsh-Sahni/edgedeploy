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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    /** DNS-safe identifier used in deployment hostnames; unique per user. */
    @Column(nullable = false, length = 63)
    private String slug;

    /** GitHub "owner/name", as GitHub spells it. */
    @Column(nullable = false, length = 200)
    private String repository;

    /** GitHub account (user or organisation) that owns the repository. */
    @Column(nullable = false, length = 39)
    private String owner;

    /** GitHub's numeric repository id; stable across renames and transfers. */
    @Column(name = "github_repository_id")
    private Long githubRepositoryId;

    @Column(nullable = false)
    private String branch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Framework framework;

    @Column(name = "default_build_command", length = 500)
    private String defaultBuildCommand;

    @Column(name = "default_start_command", length = 500)
    private String defaultStartCommand;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    public Project(User user, String name, String slug, String repository, String owner, Long githubRepositoryId,
                   String branch, Framework framework, String defaultBuildCommand, String defaultStartCommand) {
        this.user = user;
        this.name = name;
        this.slug = slug;
        this.repository = repository;
        this.owner = owner;
        this.githubRepositoryId = githubRepositoryId;
        this.branch = branch;
        this.framework = framework;
        this.defaultBuildCommand = defaultBuildCommand;
        this.defaultStartCommand = defaultStartCommand;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeBranch(String branch) {
        this.branch = branch;
    }

    public void changeFramework(Framework framework) {
        this.framework = framework;
    }

    public void changeCommands(String buildCommand, String startCommand) {
        this.defaultBuildCommand = buildCommand;
        this.defaultStartCommand = startCommand;
    }

    public User getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getRepository() {
        return repository;
    }

    public String getOwner() {
        return owner;
    }

    public Long getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public String getBranch() {
        return branch;
    }

    public Framework getFramework() {
        return framework;
    }

    public String getDefaultBuildCommand() {
        return defaultBuildCommand;
    }

    public String getDefaultStartCommand() {
        return defaultStartCommand;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
