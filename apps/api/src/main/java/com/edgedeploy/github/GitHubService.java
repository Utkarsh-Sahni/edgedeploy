package com.edgedeploy.github;

import com.edgedeploy.github.GitHubModels.Branch;
import com.edgedeploy.github.GitHubModels.Commit;
import com.edgedeploy.github.GitHubModels.Page;
import com.edgedeploy.github.GitHubModels.Repository;
import com.edgedeploy.github.GitHubModels.User;
import com.edgedeploy.github.GitHubModels.Webhook;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * GitHub operations performed on behalf of an EdgeDeploy user. Resolves the user's (decrypted)
 * token server-side; tokens never leave this layer and never reach the browser.
 */
@Service
public class GitHubService {

    private final GitHubClient client;
    private final GitHubTokenStore tokens;

    public GitHubService(GitHubClient client, GitHubTokenStore tokens) {
        this.client = client;
        this.tokens = tokens;
    }

    public User getCurrentUser(UUID userId) {
        return client.getAuthenticatedUser(token(userId));
    }

    public Page<Repository> getUserRepositories(UUID userId, int page, int perPage) {
        return client.listRepositories(token(userId), page, perPage);
    }

    public Repository getRepository(UUID userId, String owner, String repo) {
        return client.getRepository(token(userId), owner, repo);
    }

    public Page<Branch> getBranches(UUID userId, String owner, String repo, int page, int perPage) {
        return client.listBranches(token(userId), owner, repo, page, perPage);
    }

    public Branch getBranch(UUID userId, String owner, String repo, String branch) {
        return client.getBranch(token(userId), owner, repo, branch);
    }

    public Commit getCommit(UUID userId, String owner, String repo, String ref) {
        return client.getCommit(token(userId), owner, repo, ref);
    }

    /** Push-to-deploy hook. Not called yet: it needs the admin:repo_hook scope and a public webhook endpoint. */
    public Webhook createWebhook(UUID userId, String owner, String repo, String payloadUrl, String secret) {
        return client.createWebhook(token(userId), owner, repo, payloadUrl, secret, List.of("push"));
    }

    private String token(UUID userId) {
        return tokens.findAccessToken(userId).orElseThrow(() -> new GitHubApiException(
                GitHubApiException.Kind.UNAUTHORIZED, 0, "No GitHub authorization on record"));
    }
}
