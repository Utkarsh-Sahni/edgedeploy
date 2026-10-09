package com.edgedeploy.worker.github;

import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.logging.SecretRedactor;
import com.edgedeploy.worker.process.ProcessResult;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.support.GitFixture;
import com.edgedeploy.worker.support.TestProperties;
import com.edgedeploy.worker.workspace.Workspace;
import com.edgedeploy.worker.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real git CLI against local repositories served over file://. */
class CliGitServiceTest {

    @TempDir
    Path remotes;
    @TempDir
    Path workspaces;

    private GitFixture fixture;
    private CliGitService git;
    private WorkspaceManager workspaceManager;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new GitFixture(remotes);
        WorkerProperties properties = TestProperties.create(workspaces, fixture.baseUrl());
        git = new CliGitService(new ProcessRunner(), new DeployTokenCredentialsProvider(properties),
                new SecretRedactor(properties), properties);
        workspaceManager = new WorkspaceManager(properties);
        workspace = workspaceManager.create(UUID.randomUUID());
    }

    @Test
    void checksOutTheExactRequestedCommitEvenWhenTheBranchHasMovedOn() throws Exception {
        Path repo = fixture.createRepository("octocat", "app", true);
        String first = fixture.commit(repo, Map.of("version.txt", "v1"), "first");
        fixture.commit(repo, Map.of("version.txt", "v2"), "second");

        GitService.Request request = request("octocat/app", first);
        git.cloneRepository(request);
        String head = git.checkoutCommit(request);

        assertThat(head).isEqualTo(first);
        assertThat(workspace.source().resolve("version.txt")).hasContent("v1");
    }

    @Test
    void fallsBackToTheBranchWhenTheServerRefusesFetchBySha() throws Exception {
        Path repo = fixture.createRepository("octocat", "strict", false);
        String first = fixture.commit(repo, Map.of("version.txt", "v1"), "first");
        fixture.commit(repo, Map.of("version.txt", "v2"), "second");

        GitService.Request request = request("octocat/strict", first);
        git.cloneRepository(request);

        assertThat(git.checkoutCommit(request)).isEqualTo(first);
        assertThat(workspace.source().resolve("version.txt")).hasContent("v1");
    }

    @Test
    void unknownCommitFailsWithCommitNotFound() throws Exception {
        Path repo = fixture.createRepository("octocat", "app2", true);
        fixture.commit(repo, Map.of("a.txt", "a"), "only");
        String missing = "0123456789abcdef0123456789abcdef01234567";

        GitService.Request request = request("octocat/app2", missing);
        assertThatThrownBy(() -> {
            git.cloneRepository(request);
            git.checkoutCommit(request);
        }).isInstanceOfSatisfying(GitException.class, e -> {
            assertThat(e.kind()).isEqualTo(GitException.Kind.COMMIT_NOT_FOUND);
            assertThat(e.getMessage()).contains("0123456").contains("octocat/app2");
        });
    }

    @Test
    void branchTipIsResolvedWhenNoCommitIsRequested() throws Exception {
        Path repo = fixture.createRepository("octocat", "tip", true);
        fixture.commit(repo, Map.of("a.txt", "a"), "first");
        String tip = fixture.commit(repo, Map.of("a.txt", "b"), "second");

        GitService.Request request = new GitService.Request("octocat/tip", "main", null, workspace, Duration.ofSeconds(30), () -> false);
        git.cloneRepository(request);

        assertThat(git.checkoutCommit(request)).isEqualTo(tip);
    }

    @Test
    void symbolicLinksAreCheckedOutAsPlainFiles() throws Exception {
        Path repo = fixture.createRepository("octocat", "links", true);
        fixture.commit(repo, Map.of("package.json", "{}"), "first");
        String sha = fixture.commitSymlink(repo, "evil.json", "/etc/hosts");

        GitService.Request request = request("octocat/links", sha);
        git.cloneRepository(request);
        git.checkoutCommit(request);

        Path evil = workspace.source().resolve("evil.json");
        assertThat(Files.isSymbolicLink(evil)).isFalse();
        assertThat(Files.isRegularFile(evil, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(evil).hasContent("/etc/hosts"); // the link target as text, not the host file
    }

    @Test
    void cleanupRemovesTheSource() throws Exception {
        Path repo = fixture.createRepository("octocat", "clean", true);
        String sha = fixture.commit(repo, Map.of("a.txt", "a"), "first");
        GitService.Request request = request("octocat/clean", sha);
        git.cloneRepository(request);
        git.checkoutCommit(request);

        git.cleanup(workspace);

        assertThat(workspace.source()).doesNotExist();
    }

    @Test
    void credentialsTravelOnlyThroughTheEnvironmentNeverArgumentsOrDisk() throws Exception {
        Path repo = fixture.createRepository("octocat", "secret", true);
        String sha = fixture.commit(repo, Map.of("a.txt", "a"), "first");
        GitService.Request request = request("octocat/secret", sha);

        Map<String, String> env = git.environment(request);
        assertThat(env.values()).anyMatch(v -> v.startsWith("Authorization: Basic "));
        assertThat(env).containsEntry("GIT_TERMINAL_PROMPT", "0").containsEntry("GIT_CONFIG_NOSYSTEM", "1");
        assertThat(env).doesNotContainKey("GITHUB_DEPLOY_TOKEN");
        assertThat(git.remoteUrl("octocat/secret")).doesNotContain(TestProperties.DEPLOY_TOKEN).doesNotContain("@");

        git.cloneRepository(request);
        git.checkoutCommit(request);
        assertThat(Files.readString(workspace.source().resolve(".git/config"))).doesNotContain("Authorization")
                .doesNotContain(TestProperties.DEPLOY_TOKEN);
    }

    @Test
    void rejectsRepositoryAndBranchNamesThatCouldBeMisinterpreted() {
        assertThatThrownBy(() -> git.cloneRepository(new GitService.Request("octocat/..", "main", null, workspace,
                Duration.ofSeconds(5), () -> false))).isInstanceOf(GitException.class).hasMessage("Invalid repository name");
        assertThatThrownBy(() -> git.cloneRepository(new GitService.Request("octocat/app", "--upload-pack=evil", null, workspace,
                Duration.ofSeconds(5), () -> false))).isInstanceOf(GitException.class).hasMessage("Invalid branch name");
    }

    @Test
    void classifiesAuthenticationFailuresWithoutEchoingSecrets() {
        GitException e = git.classify(request("octocat/private", null),
                new ProcessResult(128, List.of("remote: Repository not found.",
                        "fatal: repository 'https://x-access-token:" + TestProperties.DEPLOY_TOKEN + "@github.com/octocat/private.git/' not found")),
                "fetch");

        assertThat(e.kind()).isEqualTo(GitException.Kind.REPOSITORY_NOT_ACCESSIBLE);
        assertThat(e.getMessage()).contains("GITHUB_DEPLOY_TOKEN").doesNotContain(TestProperties.DEPLOY_TOKEN);
    }

    private GitService.Request request(String repository, String sha) {
        return new GitService.Request(repository, "main", sha, workspace, Duration.ofSeconds(30), () -> false);
    }
}
