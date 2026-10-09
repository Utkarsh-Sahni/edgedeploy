package com.edgedeploy.github;

import com.edgedeploy.github.GitHubModels.Branch;
import com.edgedeploy.github.GitHubModels.Commit;
import com.edgedeploy.github.GitHubModels.Email;
import com.edgedeploy.github.GitHubModels.Page;
import com.edgedeploy.github.GitHubModels.Repository;
import com.edgedeploy.github.GitHubModels.User;
import com.edgedeploy.github.GitHubModels.Webhook;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Thin, stateless wrapper over the GitHub REST API (v2022-11-28). Every call takes the caller's
 * access token explicitly; resolving <em>whose</em> token to use is {@link GitHubService}'s job.
 *
 * <p>Path segments are passed as URI template variables, so user-supplied owner/repo/branch names
 * are always percent-encoded and cannot alter the request path.
 */
@Component
public class GitHubClient {

    static final int MAX_PER_PAGE = 100;

    private final RestClient http;

    public GitHubClient(RestClient gitHubRestClient) {
        this.http = gitHubRestClient;
    }

    public User getAuthenticatedUser(String token) {
        return call(() -> http.get().uri("/user")
                .headers(auth(token)).retrieve().body(User.class));
    }

    /** Requires the {@code user:email} scope. */
    public List<Email> getEmails(String token) {
        return call(() -> http.get().uri("/user/emails")
                .headers(auth(token)).retrieve().body(new ParameterizedTypeReference<List<Email>>() {
                }));
    }

    /** Repositories the user owns, collaborates on, or can see through organisation membership. */
    public Page<Repository> listRepositories(String token, int page, int perPage) {
        int size = clamp(perPage);
        return page(page, size, () -> http.get()
                .uri("/user/repos?sort=pushed&direction=desc&affiliation=owner,collaborator,organization_member"
                        + "&page={page}&per_page={perPage}", page, size)
                .headers(auth(token)).retrieve()
                .toEntity(new ParameterizedTypeReference<List<Repository>>() {
                }));
    }

    public Repository getRepository(String token, String owner, String repo) {
        return call(() -> http.get().uri("/repos/{owner}/{repo}", owner, repo)
                .headers(auth(token)).retrieve().body(Repository.class));
    }

    public Page<Branch> listBranches(String token, String owner, String repo, int page, int perPage) {
        int size = clamp(perPage);
        return page(page, size, () -> http.get()
                .uri("/repos/{owner}/{repo}/branches?page={page}&per_page={perPage}", owner, repo, page, size)
                .headers(auth(token)).retrieve()
                .toEntity(new ParameterizedTypeReference<List<Branch>>() {
                }));
    }

    public Branch getBranch(String token, String owner, String repo, String branch) {
        return call(() -> http.get().uri("/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch)
                .headers(auth(token)).retrieve().body(Branch.class));
    }

    /** Resolves any commit-ish (full/short SHA, branch, tag) to a commit. */
    public Commit getCommit(String token, String owner, String repo, String ref) {
        return call(() -> http.get().uri("/repos/{owner}/{repo}/commits/{ref}", owner, repo, ref)
                .headers(auth(token)).retrieve().body(Commit.class));
    }

    /** Requires the {@code admin:repo_hook} scope (not requested yet; push-to-deploy is a later phase). */
    public Webhook createWebhook(String token, String owner, String repo, String payloadUrl, String secret,
                                 List<String> events) {
        Map<String, Object> body = Map.of(
                "name", "web",
                "active", true,
                "events", events,
                "config", Map.of("url", payloadUrl, "content_type", "json", "secret", secret, "insecure_ssl", "0"));
        return call(() -> http.post().uri("/repos/{owner}/{repo}/hooks", owner, repo)
                .headers(auth(token)).body(body).retrieve().body(Webhook.class));
    }

    private static <T> Page<T> page(int page, int perPage, Supplier<ResponseEntity<List<T>>> request) {
        ResponseEntity<List<T>> response = call(request);
        List<T> items = response.getBody() == null ? List.of() : response.getBody();
        String link = response.getHeaders().getFirst(HttpHeaders.LINK);
        return new Page<>(items, page, perPage, link != null && link.contains("rel=\"next\""));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new GitHubApiException(GitHubApiException.Kind.UNAVAILABLE, "GitHub is unreachable", e);
        }
    }

    private static java.util.function.Consumer<HttpHeaders> auth(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    private static int clamp(int perPage) {
        return Math.max(1, Math.min(MAX_PER_PAGE, perPage));
    }

    /** Maps GitHub error responses to {@link GitHubApiException}; installed on the RestClient in GitHubConfig. */
    static GitHubApiException toException(HttpStatusCode status, HttpHeaders headers) {
        int code = status.value();
        if (code == 401) {
            return new GitHubApiException(GitHubApiException.Kind.UNAUTHORIZED, code, "GitHub authorization is no longer valid");
        }
        if (code == 404) {
            return new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, code, "Not found on GitHub");
        }
        if (code == 429 || (code == 403 && "0".equals(headers.getFirst("X-RateLimit-Remaining")))) {
            return new GitHubApiException(GitHubApiException.Kind.RATE_LIMITED, code, "GitHub API rate limit exceeded");
        }
        if (status.is5xxServerError()) {
            return new GitHubApiException(GitHubApiException.Kind.UNAVAILABLE, code, "GitHub returned " + code);
        }
        return new GitHubApiException(GitHubApiException.Kind.OTHER, code, "GitHub request failed with status " + code);
    }
}
