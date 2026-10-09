package com.edgedeploy.controller;

import com.edgedeploy.dto.GitHubBranchResponse;
import com.edgedeploy.dto.GitHubRepoResponse;
import com.edgedeploy.security.AuthenticatedUser;
import com.edgedeploy.service.GitHubService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/github")
public class GitHubController {

    private final GitHubService gitHubService;

    public GitHubController(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    @GetMapping("/repos")
    public List<GitHubRepoResponse> repos(@AuthenticationPrincipal AuthenticatedUser principal) {
        return gitHubService.listRepositories(principal.getUserId());
    }

    @GetMapping("/branches")
    public List<GitHubBranchResponse> branches(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam String repository
    ) {
        return gitHubService.listBranches(principal.getUserId(), repository);
    }
}
