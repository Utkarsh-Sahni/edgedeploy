package com.edgedeploy.worker.workspace;

import java.nio.file.Path;

/**
 * Layout of one deployment's private directory.
 *
 * <pre>
 * {root}/source  the checked-out repository (untrusted content; also the Docker build context)
 * {root}/build   files EdgeDeploy generates (Dockerfile, ignore file); kept out of the user's source
 * {root}/home    HOME for git, so no user/global configuration or credential helper leaks in
 * </pre>
 */
public record Workspace(Path root) {

    public Path source() {
        return root.resolve("source");
    }

    public Path build() {
        return root.resolve("build");
    }

    public Path home() {
        return root.resolve("home");
    }
}
