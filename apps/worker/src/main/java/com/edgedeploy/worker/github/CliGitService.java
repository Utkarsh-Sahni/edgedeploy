package com.edgedeploy.worker.github;

import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.logging.SecretRedactor;
import com.edgedeploy.worker.process.ProcessException;
import com.edgedeploy.worker.process.ProcessResult;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.process.ProcessSpec;
import com.edgedeploy.worker.workspace.SafeFiles;
import com.edgedeploy.worker.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link GitService} on top of the git CLI.
 *
 * <p>Security properties:
 * <ul>
 *   <li><b>Credentials</b> are injected as an HTTP header through {@code GIT_CONFIG_*} environment
 *       variables: never on the command line (visible in {@code ps}), never written to {@code .git/config}.</li>
 *   <li><b>Isolation</b>: no system/global git config, a private {@code HOME}, no terminal prompts, so host
 *       credential helpers and settings are never used.</li>
 *   <li><b>Symlinks are checked out as plain files</b> ({@code core.symlinks=false}): a repository cannot
 *       plant a link that makes later steps read or copy host files.</li>
 *   <li>Every argument is validated and passed as a separate argv entry; nothing goes through a shell.</li>
 * </ul>
 */
@Service
public class CliGitService implements GitService {

    private static final Logger log = LoggerFactory.getLogger(CliGitService.class);
    static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/(?!\\.\\.?$)[A-Za-z0-9._-]{1,100}$");
    static final Pattern BRANCH = Pattern.compile("^(?!.*\\.\\.)(?!.*//)(?!.*@\\{)[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}(?<![/.])$");
    static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    private final ProcessRunner processes;
    private final GitCredentialsProvider credentials;
    private final SecretRedactor redactor;
    private final WorkerProperties.Git config;

    public CliGitService(ProcessRunner processes, GitCredentialsProvider credentials, SecretRedactor redactor,
                         WorkerProperties properties) {
        this.processes = processes;
        this.credentials = credentials;
        this.redactor = redactor;
        this.config = properties.git();
    }

    @Override
    public void cloneRepository(Request request) throws GitException {
        validate(request);
        Path source = request.workspace().source();
        git(request, source, "init", "--quiet");
        git(request, source, "remote", "add", "origin", remoteUrl(request.repository()));

        if (request.commitSha() == null) {
            requireSuccess(request, git(request, source, "fetch", "--no-tags", "--depth=1", "--quiet", "origin",
                    "refs/heads/" + request.branch()), "fetch branch " + request.branch());
            return;
        }
        // Fetch just the requested commit. GitHub serves reachable commits by SHA.
        ProcessResult bySha = run(request, source, List.of("fetch", "--no-tags", "--depth=1", "--quiet", "origin",
                request.commitSha()));
        if (bySha.succeeded()) {
            return;
        }
        GitException failure = classify(request, bySha, "fetch commit");
        if (failure.kind() != GitException.Kind.COMMIT_NOT_FOUND) {
            throw failure;
        }
        // Servers that refuse fetch-by-SHA: fetch the branch history and let checkout find the commit.
        log.debug("Fetch by SHA refused; fetching branch {} instead", request.branch());
        requireSuccess(request, git(request, source, "fetch", "--no-tags", "--quiet", "origin",
                "+refs/heads/" + request.branch() + ":refs/remotes/origin/" + request.branch()), "fetch branch " + request.branch());
    }

    @Override
    public String checkoutCommit(Request request) throws GitException {
        validate(request);
        Path source = request.workspace().source();
        String target = request.commitSha() != null ? request.commitSha() : "FETCH_HEAD";

        ProcessResult checkout = run(request, source, List.of("-c", "core.symlinks=false", "-c", "advice.detachedHead=false",
                "checkout", "--quiet", "--detach", target));
        if (!checkout.succeeded()) {
            GitException classified = classify(request, checkout, "checkout");
            if (classified.kind() == GitException.Kind.FAILED) {
                throw new GitException(GitException.Kind.COMMIT_NOT_FOUND, "Commit " + shortSha(target)
                        + " was not found in " + request.repository() + " (branch " + request.branch() + ")");
            }
            throw classified;
        }

        ProcessResult revParse = git(request, source, "rev-parse", "HEAD");
        requireSuccess(request, revParse, "rev-parse HEAD");
        String head = revParse.tail().isEmpty() ? "" : revParse.tail().getLast().trim();
        if (!SHA.matcher(head).matches()) {
            throw new GitException(GitException.Kind.FAILED, "Could not determine the checked-out commit");
        }
        if (request.commitSha() != null && !head.equals(request.commitSha())) {
            throw new GitException(GitException.Kind.COMMIT_MISMATCH, "Checked-out commit " + shortSha(head)
                    + " does not match the requested commit " + shortSha(request.commitSha()));
        }
        return head;
    }

    @Override
    public void cleanup(Workspace workspace) {
        try {
            SafeFiles.deleteRecursively(workspace.source());
        } catch (IOException e) {
            log.warn("Could not delete source checkout {}: {}", workspace.source(), e.toString());
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private ProcessResult git(Request request, Path dir, String... args) throws GitException {
        return run(request, dir, List.of(args));
    }

    private ProcessResult run(Request request, Path dir, List<String> args) throws GitException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(config.executable());
        command.addAll(args);
        try {
            return processes.run(new ProcessSpec(command, dir, environment(request), request.timeout(),
                    line -> { }, request.cancelled()));
        } catch (ProcessException e) {
            throw switch (e.reason()) {
                case START_FAILED -> new GitException(GitException.Kind.GIT_UNAVAILABLE,
                        "git is not installed or not executable on the build worker");
                case TIMED_OUT -> new GitException(GitException.Kind.TIMEOUT,
                        "Git operation timed out after " + request.timeout().toSeconds() + "s");
                case CANCELLED -> new GitException(GitException.Kind.CANCELLED, "Cancelled");
            };
        }
    }

    private void requireSuccess(Request request, ProcessResult result, String what) throws GitException {
        if (!result.succeeded()) {
            throw classify(request, result, what);
        }
    }

    /** Maps git's (English, LC_ALL=C) error output to a precise, credential-free message. */
    GitException classify(Request request, ProcessResult result, String what) {
        String output = result.output().toLowerCase(Locale.ROOT);
        if (output.contains("repository not found") || output.contains("could not read username")
                || output.contains("authentication failed") || output.contains("terminal prompts disabled")
                || output.contains("returned error: 403") || output.contains("returned error: 401")) {
            return new GitException(GitException.Kind.REPOSITORY_NOT_ACCESSIBLE, "Repository " + request.repository()
                    + " was not found or is not accessible. For private repositories, configure GITHUB_DEPLOY_TOKEN on the worker.");
        }
        if (output.contains("not our ref") || output.contains("couldn't find remote ref")
                || output.contains("unadvertised object") || output.contains("no such remote ref")
                || output.contains("did not match any") || output.contains("reference is not a tree")
                || output.contains("unable to read tree")) {
            return new GitException(GitException.Kind.COMMIT_NOT_FOUND, "Commit "
                    + shortSha(request.commitSha() != null ? request.commitSha() : request.branch())
                    + " was not found in " + request.repository() + " (branch " + request.branch() + ")");
        }
        if (output.contains("could not resolve host") || output.contains("failed to connect")
                || output.contains("connection timed out") || output.contains("connection refused")) {
            return new GitException(GitException.Kind.NETWORK, "Could not reach the git server for " + request.repository());
        }
        String lastLine = result.tail().isEmpty() ? "no output" : result.tail().getLast();
        return new GitException(GitException.Kind.FAILED,
                "git " + what + " failed (exit " + result.exitCode() + "): " + redactor.redact(lastLine));
    }

    /** The complete environment for git: nothing from the worker's environment except PATH. */
    Map<String, String> environment(Request request) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        env.put("HOME", request.workspace().home().toString());
        env.put("LC_ALL", "C");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");

        List<String[]> settings = new ArrayList<>();
        if (!config.allowFileProtocol()) {
            settings.add(new String[]{"protocol.file.allow", "never"});
        }
        credentials.credentialsFor(request.repository()).ifPresent(c -> {
            String basic = Base64.getEncoder().encodeToString(
                    (c.username() + ":" + c.secret()).getBytes(StandardCharsets.UTF_8));
            settings.add(new String[]{"http." + baseUrl() + "/.extraheader", "Authorization: Basic " + basic});
        });
        env.put("GIT_CONFIG_COUNT", String.valueOf(settings.size()));
        for (int i = 0; i < settings.size(); i++) {
            env.put("GIT_CONFIG_KEY_" + i, settings.get(i)[0]);
            env.put("GIT_CONFIG_VALUE_" + i, settings.get(i)[1]);
        }
        return env;
    }

    String remoteUrl(String repository) {
        return baseUrl() + "/" + repository + ".git";
    }

    private String baseUrl() {
        return config.baseUrl().replaceAll("/+$", "");
    }

    private static void validate(Request request) throws GitException {
        if (request.repository() == null || !REPOSITORY.matcher(request.repository()).matches()) {
            throw new GitException(GitException.Kind.FAILED, "Invalid repository name");
        }
        if (request.branch() == null || !BRANCH.matcher(request.branch()).matches()) {
            throw new GitException(GitException.Kind.FAILED, "Invalid branch name");
        }
        if (request.commitSha() != null && !SHA.matcher(request.commitSha()).matches()) {
            throw new GitException(GitException.Kind.FAILED, "Invalid commit SHA");
        }
    }

    private static String shortSha(String value) {
        return value != null && SHA.matcher(value).matches() ? value.substring(0, 7) : String.valueOf(value);
    }
}
