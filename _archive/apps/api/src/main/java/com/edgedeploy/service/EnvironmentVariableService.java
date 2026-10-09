package com.edgedeploy.service;

import com.edgedeploy.dto.EnvironmentVariableRequest;
import com.edgedeploy.dto.EnvironmentVariableResponse;
import com.edgedeploy.entity.EnvironmentVariable;
import com.edgedeploy.entity.Project;
import com.edgedeploy.exception.ApiException;
import com.edgedeploy.mapper.EntityMappers;
import com.edgedeploy.repository.EnvironmentVariableRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class EnvironmentVariableService {

    private final EnvironmentVariableRepository repository;
    private final ProjectService projectService;
    private final EncryptionService encryptionService;
    private final EntityMappers mappers;

    public EnvironmentVariableService(
            EnvironmentVariableRepository repository,
            ProjectService projectService,
            EncryptionService encryptionService,
            EntityMappers mappers
    ) {
        this.repository = repository;
        this.projectService = projectService;
        this.encryptionService = encryptionService;
        this.mappers = mappers;
    }

    @Transactional(readOnly = true)
    public List<EnvironmentVariableResponse> list(UUID userId, UUID projectId) {
        projectService.requireOwned(userId, projectId);
        return repository.findByProjectIdOrderByKeyAsc(projectId).stream()
                .map(mappers::toEnv)
                .toList();
    }

    @Transactional
    public EnvironmentVariableResponse upsert(UUID userId, UUID projectId, EnvironmentVariableRequest request) {
        Project project = projectService.requireOwned(userId, projectId);
        EnvironmentVariable variable = repository.findByProjectIdAndKey(projectId, request.key())
                .orElseGet(EnvironmentVariable::new);
        variable.setProject(project);
        variable.setKey(request.key());
        variable.setEncryptedValue(encryptionService.encrypt(request.value()));
        return mappers.toEnv(repository.save(variable));
    }

    @Transactional
    public void delete(UUID userId, UUID envId) {
        EnvironmentVariable variable = repository.findByIdAndProjectUserId(envId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Environment variable not found"));
        repository.delete(variable);
    }
}
