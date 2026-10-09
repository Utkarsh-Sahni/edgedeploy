package com.edgedeploy.worker.workspace;

import com.edgedeploy.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns the on-disk sandbox of each deployment: {@code {baseDir}/{deploymentId}/}.
 *
 * <ul>
 *   <li>The directory name comes from the deployment's UUID, never from repository or branch names.</li>
 *   <li>A directory is never reused: leftovers from a crashed attempt are wiped before use.</li>
 *   <li>Deletion never follows symbolic links, so a malicious repository cannot trick cleanup into
 *       deleting files outside the workspace.</li>
 * </ul>
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Path baseDir;
    private final Duration staleAfter;

    public WorkspaceManager(WorkerProperties properties) {
        if (properties.workspace().baseDir().toString().isBlank()) {
            // An empty EDGEDEPLOY_WORKSPACE_DIR would resolve to the current directory.
            throw new IllegalStateException("edgedeploy.workspace.base-dir (EDGEDEPLOY_WORKSPACE_DIR) must not be blank");
        }
        this.baseDir = properties.workspace().baseDir().toAbsolutePath().normalize();
        this.staleAfter = properties.timeouts().deployment().plusMinutes(10);
    }

    public Workspace create(UUID deploymentId) throws IOException {
        Path root = baseDir.resolve(deploymentId.toString()).normalize();
        requireInsideBase(root);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("Removing leftover workspace {}", root);
            deleteRecursively(root);
        }
        Files.createDirectories(baseDir);
        createPrivateDirectory(root);
        Workspace workspace = new Workspace(root);
        createPrivateDirectory(workspace.source());
        createPrivateDirectory(workspace.build());
        createPrivateDirectory(workspace.home());
        return workspace;
    }

    /** Best-effort, never throws: cleanup problems must not mask the deployment result. */
    public void delete(Workspace workspace) {
        try {
            requireInsideBase(workspace.root());
            deleteRecursively(workspace.root());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not fully delete workspace {}: {}", workspace.root(), e.toString());
        }
    }

    /** On startup, remove workspaces abandoned by a crash (older than any deployment could run). */
    @EventListener(ApplicationReadyEvent.class)
    public void purgeStale() {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(staleAfter);
        try (Stream<Path> children = Files.list(baseDir)) {
            children.filter(child -> isOlderThan(child, cutoff)).forEach(child -> {
                try {
                    deleteRecursively(child);
                    log.info("Purged stale workspace {}", child);
                } catch (IOException e) {
                    log.warn("Could not purge {}: {}", child, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Could not scan workspace directory {}: {}", baseDir, e.toString());
        }
    }

    public Path baseDir() {
        return baseDir;
    }

    private void requireInsideBase(Path path) {
        if (!path.normalize().startsWith(baseDir) || path.normalize().equals(baseDir)) {
            throw new IllegalArgumentException("Path escapes the workspace directory: " + path);
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
            return modified.toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private static void createPrivateDirectory(Path dir) throws IOException {
        Files.createDirectory(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX file system (Windows); the base directory's ACLs apply.
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        SafeFiles.deleteRecursively(root);
    }
}
