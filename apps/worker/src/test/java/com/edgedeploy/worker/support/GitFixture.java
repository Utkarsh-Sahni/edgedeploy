package com.edgedeploy.worker.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates real git repositories laid out like GitHub ({@code {root}/{owner}/{name}.git}) so GitService
 * can be tested against a {@code file://} base URL without network access.
 */
public final class GitFixture {

    private final Path root;

    public GitFixture(Path root) {
        this.root = root;
    }

    public String baseUrl() {
        return root.toUri().toString().replaceAll("/+$", "");
    }

    /** A repository with the given files committed on {@code main}; returns its directory. */
    public Path createRepository(String owner, String name, boolean allowFetchBySha) throws IOException, InterruptedException {
        Path repo = root.resolve(owner).resolve(name + ".git");
        Files.createDirectories(repo);
        git(repo, "init", "--quiet", "--initial-branch=main");
        git(repo, "config", "user.email", "fixture@edgedeploy.test");
        git(repo, "config", "user.name", "Fixture");
        git(repo, "config", "commit.gpgsign", "false");
        if (allowFetchBySha) {
            git(repo, "config", "uploadpack.allowAnySHA1InWant", "true");
        }
        return repo;
    }

    public String commit(Path repo, Map<String, String> files, String message) throws IOException, InterruptedException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = repo.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        git(repo, "add", "--all");
        git(repo, "commit", "--quiet", "--allow-empty", "-m", message);
        return git(repo, "rev-parse", "HEAD").trim();
    }

    public String commitSymlink(Path repo, String linkName, String target) throws IOException, InterruptedException {
        Files.createSymbolicLink(repo.resolve(linkName), Path.of(target));
        git(repo, "add", "--all");
        git(repo, "commit", "--quiet", "-m", "add symlink");
        return git(repo, "rev-parse", "HEAD").trim();
    }

    public static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
