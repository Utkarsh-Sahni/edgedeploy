package com.edgedeploy.service;

import com.edgedeploy.dto.CreateProjectRequest;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.dto.UpdateProjectRequest;
import com.edgedeploy.entity.Framework;
import com.edgedeploy.entity.Project;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.InvalidRequestException;
import com.edgedeploy.exception.ResourceNotFoundException;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubModels;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.mapper.ProjectMapper;
import com.edgedeploy.repository.ProjectRepository;
import com.edgedeploy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Project lifecycle. Every operation is scoped to the calling user: another user's project is
 * indistinguishable from a missing one (404), so ids can't be probed.
 *
 * <p>GitHub is consulted <em>outside</em> database transactions so a slow GitHub never pins a
 * connection; the transactional part then re-reads and writes.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final int MAX_SLUG_ATTEMPTS = 50;

    private final ProjectRepository projects;
    private final UserRepository users;
    private final GitHubService gitHub;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public ProjectService(ProjectRepository projects, UserRepository users, GitHubService gitHub,
                          PlatformTransactionManager transactionManager) {
        this.projects = projects;
        this.users = users;
        this.gitHub = gitHub;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /**
     * Creates a project for a GitHub repository the user can write to. The unique constraints on
     * (user_id, repository) and (user_id, slug) are the real guard; a lost race becomes a 409.
     */
    public ProjectResponse create(UUID userId, CreateProjectRequest request) {
        RepositoryName name = RepositoryName.parse(request.repository().trim());
        GitHubModels.Repository repo = verifyRepository(userId, name);
        verifyBranch(userId, name, request.branch());

        return tx.execute(status -> {
            if (projects.existsByUser_IdAndRepository(userId, repo.fullName())) {
                throw new ConflictException("A project for " + repo.fullName() + " already exists");
            }
            Project project = new Project(
                    users.getReferenceById(userId),
                    request.name().trim(),
                    uniqueSlug(userId, request.name()),
                    repo.fullName(),
                    repo.owner().login(),
                    repo.id(),
                    request.branch(),
                    request.framework() != null ? request.framework() : Framework.UNKNOWN,
                    blankToNull(request.buildCommand()),
                    blankToNull(request.startCommand()));
            projects.saveAndFlush(project);
            log.info("Created project {} ({}) for {}@{}", project.getId(), project.getSlug(), repo.fullName(),
                    request.branch());
            return ProjectMapper.toResponse(project);
        });
    }

    public List<ProjectResponse> list(UUID userId) {
        return readOnlyTx.execute(status -> projects.findAllByUser_IdOrderByCreatedAtDesc(userId).stream()
                .map(ProjectMapper::toResponse)
                .toList());
    }

    public ProjectResponse get(UUID userId, UUID projectId) {
        return readOnlyTx.execute(status -> ProjectMapper.toResponse(getOwned(userId, projectId)));
    }

    public ProjectResponse update(UUID userId, UUID projectId, UpdateProjectRequest request) {
        ProjectResponse current = get(userId, projectId);
        if (request.branch() != null && !request.branch().equals(current.branch())) {
            verifyBranch(userId, RepositoryName.parse(current.repository()), request.branch());
        }

        return tx.execute(status -> {
            Project project = getOwned(userId, projectId);
            if (request.name() != null) {
                project.rename(request.name().trim()); // slug stays: deployment hostnames must not move
            }
            if (request.branch() != null) {
                project.changeBranch(request.branch());
            }
            if (request.framework() != null) {
                project.changeFramework(request.framework());
            }
            if (request.buildCommand() != null || request.startCommand() != null) {
                project.changeCommands(
                        request.buildCommand() != null ? blankToNull(request.buildCommand()) : project.getDefaultBuildCommand(),
                        request.startCommand() != null ? blankToNull(request.startCommand()) : project.getDefaultStartCommand());
            }
            projects.flush();
            return ProjectMapper.toResponse(project);
        });
    }

    /** Deletes the project and, via ON DELETE CASCADE, its deployments, logs and settings. */
    public void delete(UUID userId, UUID projectId) {
        tx.executeWithoutResult(status -> {
            Project project = getOwned(userId, projectId);
            projects.delete(project);
            log.info("Deleted project {} ({})", projectId, project.getRepository());
        });
    }

    /** As {@link #getOwned} but row-locked until the caller's transaction ends. */
    Project getOwnedForUpdate(UUID userId, UUID projectId) {
        return projects.findOwnedForUpdate(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    /** For other services, inside their own transaction. */
    Project getOwned(UUID userId, UUID projectId) {
        return projects.findByIdAndUser_Id(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    }

    private GitHubModels.Repository verifyRepository(UUID userId, RepositoryName name) {
        GitHubModels.Repository repo;
        try {
            repo = gitHub.getRepository(userId, name.owner(), name.repo());
        } catch (GitHubApiException e) {
            if (e.kind() == GitHubApiException.Kind.NOT_FOUND) {
                throw new InvalidRequestException("repository",
                        "Repository " + name + " was not found or is not accessible with your GitHub account");
            }
            throw e;
        }
        if (!repo.canDeploy()) {
            throw new InvalidRequestException("repository", "You need write access to " + repo.fullName() + " to deploy it");
        }
        return repo;
    }

    private void verifyBranch(UUID userId, RepositoryName name, String branch) {
        try {
            gitHub.getBranch(userId, name.owner(), name.repo(), branch);
        } catch (GitHubApiException e) {
            if (e.kind() == GitHubApiException.Kind.NOT_FOUND) {
                throw new InvalidRequestException("branch", "Branch '" + branch + "' does not exist in " + name);
            }
            throw e;
        }
    }

    private String uniqueSlug(UUID userId, String name) {
        String base = Slugs.slugify(name);
        String candidate = base;
        for (int i = 2; projects.existsByUser_IdAndSlug(userId, candidate); i++) {
            if (i > MAX_SLUG_ATTEMPTS) {
                throw new ConflictException("Too many projects named '" + name + "'");
            }
            candidate = base + "-" + i;
        }
        return candidate;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** "owner/name", already validated by the request pattern. */
    record RepositoryName(String owner, String repo) {
        static RepositoryName parse(String fullName) {
            int slash = fullName.indexOf('/');
            return new RepositoryName(fullName.substring(0, slash), fullName.substring(slash + 1));
        }

        @Override
        public String toString() {
            return owner + "/" + repo;
        }
    }
}
