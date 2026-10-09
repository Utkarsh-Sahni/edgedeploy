package com.edgedeploy.service;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.dto.CreateProjectRequest;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.entity.Domain;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.exception.ApiException;
import com.edgedeploy.mapper.EntityMappers;
import com.edgedeploy.repository.DeploymentRepository;
import com.edgedeploy.repository.DomainRepository;
import com.edgedeploy.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final DeploymentRepository deploymentRepository;
    private final DomainRepository domainRepository;
    private final UserService userService;
    private final EntityMappers mappers;
    private final EdgeDeployProperties properties;

    public ProjectService(
            ProjectRepository projectRepository,
            DeploymentRepository deploymentRepository,
            DomainRepository domainRepository,
            UserService userService,
            EntityMappers mappers,
            EdgeDeployProperties properties
    ) {
        this.projectRepository = projectRepository;
        this.deploymentRepository = deploymentRepository;
        this.domainRepository = domainRepository;
        this.userService = userService;
        this.mappers = mappers;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(UUID userId) {
        return projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProjectResponse create(UUID userId, CreateProjectRequest request) {
        User user = userService.getRequired(userId);
        projectRepository.findByUserIdAndRepository(userId, request.repository()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "Project already exists for this repository");
        });
        Project project = new Project();
        project.setUser(user);
        project.setName(request.name());
        project.setRepository(request.repository());
        project.setBranch(request.branch());
        project.setFramework(request.framework());
        Project saved = projectRepository.save(project);

        Domain domain = new Domain();
        domain.setProject(saved);
        domain.setHostname(defaultHostname(saved.getId()));
        domain.setStatus("RESERVED");
        domainRepository.save(domain);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID userId, UUID projectId) {
        return toResponse(requireOwned(userId, projectId));
    }

    public Project requireOwned(UUID userId, UUID projectId) {
        return projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    public Project findByRepository(String repository) {
        return projectRepository.findByRepository(repository)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No project mapped to " + repository));
    }

    private ProjectResponse toResponse(Project project) {
        return mappers.toProject(
                project,
                deploymentRepository.findFirstByProjectIdOrderByCreatedAtDesc(project.getId()).orElse(null)
        );
    }

    private String defaultHostname(UUID projectId) {
        String shortId = projectId.toString().substring(0, 8);
        return shortId + "." + properties.getDeployment().getBaseDomain();
    }
}
