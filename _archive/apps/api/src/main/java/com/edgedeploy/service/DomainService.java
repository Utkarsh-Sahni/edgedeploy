package com.edgedeploy.service;

import com.edgedeploy.dto.DomainResponse;
import com.edgedeploy.mapper.EntityMappers;
import com.edgedeploy.repository.DomainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DomainService {

    private final DomainRepository domainRepository;
    private final ProjectService projectService;
    private final EntityMappers mappers;

    public DomainService(DomainRepository domainRepository, ProjectService projectService, EntityMappers mappers) {
        this.domainRepository = domainRepository;
        this.projectService = projectService;
        this.mappers = mappers;
    }

    @Transactional(readOnly = true)
    public List<DomainResponse> list(UUID userId, UUID projectId) {
        projectService.requireOwned(userId, projectId);
        return domainRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(mappers::toDomain)
                .toList();
    }
}
