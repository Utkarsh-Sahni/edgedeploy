package com.edgedeploy.worker.support;

import com.edgedeploy.worker.config.WorkerProperties;

import java.nio.file.Path;
import java.time.Duration;

/** WorkerProperties for unit tests. */
public final class TestProperties {

    public static final String DEPLOY_TOKEN = "ghp_TestDeployToken000000000000000000000";
    public static final String ENCRYPTION_KEY = "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLWtleSE=";

    private TestProperties() {
    }

    public static WorkerProperties create(Path workspaceDir, String gitBaseUrl) {
        return new WorkerProperties(
                WorkerProperties.Mode.BUILD,
                new WorkerProperties.Kafka(1, 1, new WorkerProperties.Retry(0, Duration.ofMillis(100), 1.0, Duration.ofMillis(100))),
                new WorkerProperties.Timeouts(Duration.ofMinutes(5), Duration.ofMinutes(3), Duration.ofSeconds(30),
                        Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(1)),
                new WorkerProperties.Workspace(workspaceDir),
                new WorkerProperties.Git("git", gitBaseUrl, DEPLOY_TOKEN, true),
                new WorkerProperties.Docker("docker", "edgedeploy", "node:22-alpine", "nginxinc/nginx-unprivileged:1.27-alpine", 3000, ""),
                new WorkerProperties.Logs(100, 5, Duration.ofSeconds(1)),
                new WorkerProperties.Encryption(ENCRYPTION_KEY));
    }
}
