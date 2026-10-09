package com.edgedeploy.service;

import com.edgedeploy.config.EdgeDeployProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final EdgeDeployProperties properties;

    public RateLimitService(StringRedisTemplate redisTemplate, EdgeDeployProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean allow(String key) {
        String redisKey = "ratelimit:" + key;
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, Duration.ofMinutes(1));
        }
        return count == null || count <= properties.getRateLimit().getRequestsPerMinute();
    }
}
