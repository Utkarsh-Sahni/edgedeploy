package com.edgedeploy.git;

import com.edgedeploy.config.WorkerProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class GitCloneService {

    private final WorkerProperties properties;

    public GitCloneService(WorkerProperties properties) {
        this.properties = properties;
    }

    public Path cloneRepository(UUID deploymentId, String repository, String commitSha, String accessToken)
            throws Exception {
        Path workDir = Path.of(properties.getWorkspace(), deploymentId.toString());
        Files.createDirectories(workDir.getParent());
        if (Files.exists(workDir)) {
            deleteRecursively(workDir);
        }
        String uri = "https://github.com/" + repository + ".git";
        var clone = Git.cloneRepository()
                .setURI(uri)
                .setDirectory(workDir.toFile());
        var credentials = credentials(accessToken);
        if (credentials != null) {
            clone.setCredentialsProvider(credentials);
        }
        try (Git git = clone.call()) {
            git.checkout().setName(commitSha).call();
        }
        return workDir;
    }

    private UsernamePasswordCredentialsProvider credentials(String accessToken) {
        String token = accessToken;
        if (token == null || token.isBlank()) {
            token = properties.getGithub().getToken();
        }
        if (token == null || token.isBlank()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    private void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
