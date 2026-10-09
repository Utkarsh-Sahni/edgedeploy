package com.edgedeploy.github;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.dto.GitHubBranchResponse;
import com.edgedeploy.dto.GitHubRepoResponse;
import com.edgedeploy.exception.ApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class GitHubClient {

    private final RestClient restClient;

    public GitHubClient(RestClient.Builder builder, EdgeDeployProperties properties) {
        this.restClient = builder.baseUrl(properties.getGithub().getApiBaseUrl()).build();
    }

    public List<GitHubRepoResponse> listRepositories(String accessToken) {
        List<Map<String, Object>> payload = restClient.get()
                .uri("/user/repos?per_page=100&sort=updated")
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (payload == null) {
            return List.of();
        }
        return payload.stream()
                .map(repo -> new GitHubRepoResponse(
                        String.valueOf(repo.get("full_name")),
                        String.valueOf(repo.get("default_branch")),
                        Boolean.TRUE.equals(repo.get("private")),
                        String.valueOf(repo.get("html_url"))
                ))
                .toList();
    }

    public List<GitHubBranchResponse> listBranches(String accessToken, String repository) {
        List<Map<String, Object>> payload = restClient.get()
                .uri("/repos/{repo}/branches?per_page=100", repository)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (payload == null) {
            return List.of();
        }
        return payload.stream()
                .map(branch -> {
                    Map<String, Object> commit = castMap(branch.get("commit"));
                    return new GitHubBranchResponse(
                            String.valueOf(branch.get("name")),
                            commit == null ? null : String.valueOf(commit.get("sha"))
                    );
                })
                .toList();
    }

    public String resolveCommitSha(String accessToken, String repository, String branch) {
        Map<String, Object> payload = restClient.get()
                .uri("/repos/{repo}/commits/{sha}", repository, branch)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (payload == null || payload.get("sha") == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unable to resolve commit for branch " + branch);
        }
        return String.valueOf(payload.get("sha"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }
}
