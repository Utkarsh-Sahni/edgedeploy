package com.edgedeploy.mapper;

import com.edgedeploy.deployment.DeploymentStatus;
import com.edgedeploy.dto.DeploymentLogResponse;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.dto.DomainResponse;
import com.edgedeploy.dto.EnvironmentVariableResponse;
import com.edgedeploy.dto.ProjectResponse;
import com.edgedeploy.dto.UserResponse;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.entity.Domain;
import com.edgedeploy.entity.EnvironmentVariable;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import org.springframework.stereotype.Component;

@Component
public class EntityMappers {

    public UserResponse toUser(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getGithubId(), user.getGithubLogin());
    }

    public ProjectResponse toProject(Project project, Deployment latest) {
        DeploymentStatus status = latest == null ? null : latest.getStatus();
        String url = latest == null ? null : latest.getDeploymentUrl();
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getRepository(),
                project.getBranch(),
                project.getFramework(),
                status == null ? null : status.name(),
                url,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    public DeploymentResponse toDeployment(Deployment deployment) {
        return new DeploymentResponse(
                deployment.getId(),
                deployment.getProject().getId(),
                deployment.getCommitSha(),
                deployment.getStatus(),
                deployment.getImageUri(),
                deployment.getDeploymentUrl(),
                deployment.getErrorMessage(),
                deployment.getStartedAt(),
                deployment.getCompletedAt(),
                deployment.getCreatedAt()
        );
    }

    public DeploymentLogResponse toLog(DeploymentLog log) {
        return new DeploymentLogResponse(log.getId(), log.getLevel(), log.getMessage(), log.getTimestamp());
    }

    public EnvironmentVariableResponse toEnv(EnvironmentVariable variable) {
        return new EnvironmentVariableResponse(
                variable.getId(),
                variable.getKey(),
                variable.getCreatedAt(),
                variable.getUpdatedAt()
        );
    }

    public DomainResponse toDomain(Domain domain) {
        return new DomainResponse(domain.getId(), domain.getHostname(), domain.getStatus(), domain.getCreatedAt());
    }
}
