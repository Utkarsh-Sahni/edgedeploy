package com.edgedeploy.worker.workspace;

import com.edgedeploy.worker.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceManagerTest {

    @TempDir
    Path base;
    @TempDir
    Path outside;

    @Test
    void createsAPrivateWorkspacePerDeploymentAndDeletesItCompletely() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(TestProperties.create(base, "https://github.com"));
        UUID id = UUID.randomUUID();

        Workspace workspace = manager.create(id);
        Files.writeString(workspace.source().resolve("file.txt"), "x");
        Files.createDirectories(workspace.source().resolve(".git/objects"));
        Path readOnly = workspace.source().resolve(".git/objects/pack");
        Files.writeString(readOnly, "pack");
        readOnly.toFile().setWritable(false);

        assertThat(workspace.root()).isEqualTo(base.resolve(id.toString()));
        assertThat(workspace.source()).isDirectory();
        assertThat(workspace.build()).isDirectory();

        manager.delete(workspace);
        assertThat(workspace.root()).doesNotExist();
    }

    @Test
    void neverReusesALeftoverWorkspace() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(TestProperties.create(base, "https://github.com"));
        UUID id = UUID.randomUUID();
        Workspace first = manager.create(id);
        Files.writeString(first.source().resolve("stale.txt"), "from a crashed attempt");

        Workspace second = manager.create(id);

        assertThat(second.source().resolve("stale.txt")).doesNotExist();
    }

    @Test
    void cleanupDeletesSymlinksButNeverTheirTargets() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(TestProperties.create(base, "https://github.com"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
        Workspace workspace = manager.create(UUID.randomUUID());
        Files.createSymbolicLink(workspace.source().resolve("link-to-file"), precious);
        Files.createSymbolicLink(workspace.source().resolve("link-to-dir"), outside);

        manager.delete(workspace);

        assertThat(workspace.root()).doesNotExist();
        assertThat(precious).hasContent("keep me");
    }

    @Test
    void safeFilesRejectsTraversalAndSymlinks() throws Exception {
        Path precious = Files.writeString(outside.resolve("package.json"), "{\"secret\":true}");
        Files.createSymbolicLink(base.resolve("package.json"), precious);

        assertThat(SafeFiles.isRegularFile(base, "package.json")).isFalse();
        assertThat(SafeFiles.readRegularFile(base, "package.json", 1024)).isEmpty();
        assertThatThrownBy(() -> SafeFiles.readRegularFile(base, "../package.json", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
