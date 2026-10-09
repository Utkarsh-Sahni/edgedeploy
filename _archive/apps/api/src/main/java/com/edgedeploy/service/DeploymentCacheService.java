package com.edgedeploy.service;

import com.edgedeploy.deployment.DeploymentStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeploymentCacheService {

    private final StringRedisTemplate redisTemplate;

    public DeploymentCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void cacheStatus(UUID deploymentId, DeploymentStatus status) {
        redisTemplate.opsForValue().set("deployment:status:" + deploymentId, status.name(), Duration.ofHours(1));
    }

    public Optional<DeploymentStatus> getStatus(UUID deploymentId) {
        String value = redisTemplate.opsForValue().get("deployment:status:" + deploymentId);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(DeploymentStatus.valueOf(value));
    }
}
