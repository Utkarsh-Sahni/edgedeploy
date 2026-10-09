package com.edgedeploy.deployment;

import com.edgedeploy.entity.Deployment;
import com.edgedeploy.repository.DeploymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeploymentClaimService {

    private final DeploymentRepository deploymentRepository;

    public DeploymentClaimService(DeploymentRepository deploymentRepository) {
        this.deploymentRepository = deploymentRepository;
    }

    @Transactional
    public Deployment claim(UUID deploymentId) {
        Deployment deployment = deploymentRepository.findById(deploymentId).orElse(null);
        if (deployment == null || deployment.getStatus() != DeploymentStatus.QUEUED) {
            return null;
        }
        deployment.setStatus(DeploymentStatus.BUILDING);
        deployment.setStartedAt(Instant.now());
        return deploymentRepository.save(deployment);
    }
}
