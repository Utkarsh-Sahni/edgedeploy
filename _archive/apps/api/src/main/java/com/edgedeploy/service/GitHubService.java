package com.edgedeploy.service;

import com.edgedeploy.dto.GitHubBranchResponse;
import com.edgedeploy.dto.GitHubRepoResponse;
import com.edgedeploy.entity.User;
import com.edgedeploy.github.GitHubClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class GitHubService {

    private final UserService userService;
    private final GitHubClient gitHubClient;
    private final StringRedisTemplate redisTemplate;

    public GitHubService(UserService userService, GitHubClient gitHubClient, StringRedisTemplate redisTemplate) {
        this.userService = userService;
        this.gitHubClient = gitHubClient;
        this.redisTemplate = redisTemplate;
    }

    public List<GitHubRepoResponse> listRepositories(UUID userId) {
        User user = userService.getRequired(userId);
        String cacheKey = "github:repos:" + userId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // Cache is a presence marker to avoid hammering GitHub; still fetch live list for correctness in MVP.
        }
        List<GitHubRepoResponse> repos = gitHubClient.listRepositories(userService.decryptGithubToken(user));
        redisTemplate.opsForValue().set(cacheKey, "1", Duration.ofSeconds(30));
        return repos;
    }

    public List<GitHubBranchResponse> listBranches(UUID userId, String repository) {
        User user = userService.getRequired(userId);
        return gitHubClient.listBranches(userService.decryptGithubToken(user), repository);
    }
}
