package com.edgedeploy.mapper;

import com.edgedeploy.dto.GitHubBranchResponse;
import com.edgedeploy.dto.GitHubRepositoryResponse;
import com.edgedeploy.dto.PageResponse;
import com.edgedeploy.github.GitHubModels;

import java.util.function.Function;

public final class GitHubMapper {

    private GitHubMapper() {
    }

    public static GitHubRepositoryResponse toResponse(GitHubModels.Repository repo) {
        return new GitHubRepositoryResponse(
                repo.id(),
                repo.name(),
                repo.fullName(),
                repo.owner() != null ? repo.owner().login() : null,
                repo.description(),
                repo.defaultBranch(),
                repo.isPrivate(),
                repo.htmlUrl(),
                repo.language(),
                repo.pushedAt(),
                repo.canDeploy());
    }

    public static GitHubBranchResponse toResponse(GitHubModels.Branch branch) {
        return new GitHubBranchResponse(branch.name(), branch.commit() != null ? branch.commit().sha() : null,
                branch.isProtected());
    }

    public static <S, T> PageResponse<T> toPage(GitHubModels.Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.items().stream().map(mapper).toList(), page.page(), page.perPage(), page.hasNext());
    }
}
