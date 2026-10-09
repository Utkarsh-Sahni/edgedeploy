package com.edgedeploy.deployment;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final StringRedisTemplate redisTemplate;

    public IdempotencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryLock(UUID deploymentId) {
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent("lock:deployment:" + deploymentId, "1", Duration.ofMinutes(30));
        return Boolean.TRUE.equals(acquired);
    }

    public void unlock(UUID deploymentId) {
        redisTemplate.delete("lock:deployment:" + deploymentId);
    }
}
