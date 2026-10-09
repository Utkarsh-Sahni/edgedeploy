package com.edgedeploy.kafka;

import com.edgedeploy.deployment.DeploymentStatus;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.repository.DeploymentLogRepository;
import com.edgedeploy.repository.DeploymentRepository;
import com.edgedeploy.service.DeploymentCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DeploymentStatusListener {

    private static final Logger log = LoggerFactory.getLogger(DeploymentStatusListener.class);

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final DeploymentCacheService cacheService;

    public DeploymentStatusListener(
            DeploymentRepository deploymentRepository,
            DeploymentLogRepository deploymentLogRepository,
            DeploymentCacheService cacheService
    ) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentLogRepository = deploymentLogRepository;
        this.cacheService = cacheService;
    }

    @KafkaListener(
            topics = "${edgedeploy.kafka.topics.status}",
            groupId = "edgedeploy-api-status"
    )
    @Transactional
    public void onStatus(DeploymentStatusEvent event) {
        Deployment deployment = deploymentRepository.findById(event.deploymentId()).orElse(null);
        if (deployment == null) {
            log.warn("Received status for unknown deployment {}", event.deploymentId());
            return;
        }
        deployment.setStatus(event.status());
        if (event.deploymentUrl() != null) {
            deployment.setDeploymentUrl(event.deploymentUrl());
        }
        if (event.status() == DeploymentStatus.FAILED && event.message() != null) {
            deployment.setErrorMessage(event.message());
        }
        if (event.status() == DeploymentStatus.RUNNING || event.status() == DeploymentStatus.FAILED) {
            deployment.setCompletedAt(event.timestamp());
        }
        if (event.status() == DeploymentStatus.BUILDING && deployment.getStartedAt() == null) {
            deployment.setStartedAt(event.timestamp());
        }
        deploymentRepository.save(deployment);
        cacheService.cacheStatus(deployment.getId(), event.status());

        if (event.message() != null && !event.message().isBlank()) {
            DeploymentLog logEntry = new DeploymentLog();
            logEntry.setDeployment(deployment);
            logEntry.setLevel(event.status() == DeploymentStatus.FAILED ? "ERROR" : "INFO");
            logEntry.setMessage(event.message());
            logEntry.setTimestamp(event.timestamp());
            deploymentLogRepository.save(logEntry);
        }
    }
}
