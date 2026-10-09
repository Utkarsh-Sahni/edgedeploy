package com.edgedeploy.controller;

import com.edgedeploy.dto.GitHubBranchResponse;
import com.edgedeploy.dto.GitHubRepositoryResponse;
import com.edgedeploy.dto.PageResponse;
import com.edgedeploy.dto.ValidationPatterns;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.mapper.GitHubMapper;
import com.edgedeploy.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views of the signed-in user's GitHub data, fetched server-side with their stored token. */
@RestController
@RequestMapping("/api/github")
@Tag(name = "GitHub", description = "Repositories and branches visible to the signed-in user. "
        + "401 with code `github_reauth_required` means the GitHub authorization must be renewed by signing in again.")
@ApiErrorResponses.GitHubBacked
public class GitHubController {

    private final GitHubService gitHub;
    private final CurrentUserProvider currentUser;

    public GitHubController(GitHubService gitHub, CurrentUserProvider currentUser) {
        this.gitHub = gitHub;
        this.currentUser = currentUser;
    }

    @GetMapping("/repositories")
    @Operation(summary = "List repositories", description = "Most recently pushed first. `canDeploy` is true when you have write access.")
    @ApiErrorResponses.BadRequest
    public PageResponse<GitHubRepositoryResponse> repositories(
            @RequestParam(defaultValue = "1") @Min(1) @Max(100) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int perPage) {
        return GitHubMapper.toPage(gitHub.getUserRepositories(currentUser.currentUserId(), page, perPage),
                GitHubMapper::toResponse);
    }

    @GetMapping("/repositories/{owner}/{repo}")
    @Operation(summary = "Get a repository")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    public GitHubRepositoryResponse repository(
            @PathVariable @Pattern(regexp = ValidationPatterns.GITHUB_OWNER) String owner,
            @PathVariable @Pattern(regexp = ValidationPatterns.GITHUB_REPO_NAME) String repo) {
        return GitHubMapper.toResponse(gitHub.getRepository(currentUser.currentUserId(), owner, repo));
    }

    @GetMapping("/repositories/{owner}/{repo}/branches")
    @Operation(summary = "List branches of a repository")
    @ApiErrorResponses.BadRequest
    @ApiErrorResponses.NotFound
    public PageResponse<GitHubBranchResponse> branches(
            @PathVariable @Pattern(regexp = ValidationPatterns.GITHUB_OWNER) String owner,
            @PathVariable @Pattern(regexp = ValidationPatterns.GITHUB_REPO_NAME) String repo,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int perPage) {
        return GitHubMapper.toPage(gitHub.getBranches(currentUser.currentUserId(), owner, repo, page, perPage),
                GitHubMapper::toResponse);
    }
}
