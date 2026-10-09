package com.edgedeploy.mapper;

import com.edgedeploy.dto.DeploymentLogResponse;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.DeploymentLog;

public final class DeploymentMapper {

    private DeploymentMapper() {
    }

    /** Requires {@code deployment.getProject()} to be loadable (call inside a transaction or fetch-join it). */
    public static DeploymentResponse toResponse(Deployment deployment) {
        return new DeploymentResponse(
                deployment.getId(),
                deployment.getNumber(),
                deployment.getProject().getId(),
                deployment.getProject().getName(),
                deployment.getCommitSha(),
                deployment.getStatus(),
                deployment.getImageUri(),
                deployment.getDeploymentUrl(),
                deployment.getErrorMessage(),
                deployment.getStartedAt(),
                deployment.getCompletedAt(),
                deployment.getCreatedAt(),
                deployment.getUpdatedAt());
    }

    public static DeploymentLogResponse toResponse(DeploymentLog log) {
        return new DeploymentLogResponse(log.getId(), log.getSeq() != null ? log.getSeq() : 0, log.getLevel(), log.getStep(),
                log.getMessage(), log.getTimestamp());
    }
}
