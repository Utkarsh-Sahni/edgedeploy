package com.edgedeploy.mapper;

import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.entity.Project;

public final class ProjectMapper {

    private ProjectMapper() {
    }

    public static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getSlug(),
                project.getRepository(),
                project.getOwner(),
                project.getGithubRepositoryId(),
                project.getBranch(),
                project.getFramework(),
                project.getDefaultBuildCommand(),
                project.getDefaultStartCommand(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }
}
