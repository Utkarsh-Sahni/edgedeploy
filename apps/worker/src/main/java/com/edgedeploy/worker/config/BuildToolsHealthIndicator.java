package com.edgedeploy.worker.config;

import com.edgedeploy.worker.process.ProcessException;
import com.edgedeploy.worker.process.ProcessResult;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.process.ProcessSpec;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reports whether this worker can actually build: git installed and the Docker daemon reachable.
 * Shown at {@code /actuator/health} as {@code buildTools}.
 */
@Component("buildTools")
@ConditionalOnProperty(prefix = "edgedeploy", name = "mode", havingValue = "build", matchIfMissing = true)
public class BuildToolsHealthIndicator extends AbstractHealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ProcessRunner processes;
    private final WorkerProperties properties;

    public BuildToolsHealthIndicator(ProcessRunner processes, WorkerProperties properties) {
        super("Build tools health check failed");
        this.processes = processes;
        this.properties = properties;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        String git = probe(List.of(properties.git().executable(), "--version"));
        String docker = probe(List.of(properties.docker().executable(), "version", "--format", "{{.Server.Version}}"));
        boolean up = git != null && docker != null;
        (up ? builder.up() : builder.down())
                .withDetail("git", git != null ? git : "unavailable")
                .withDetail("dockerDaemon", docker != null ? docker : "unavailable");
    }

    private String probe(List<String> command) {
        Map<String, String> env = new java.util.HashMap<>();
        System.getenv().forEach((key, value) -> {
            if (key.equals("PATH") || key.equals("HOME") || key.startsWith("DOCKER_")) {
                env.put(key, value);
            }
        });
        try {
            ProcessResult result = processes.run(new ProcessSpec(command, Path.of(System.getProperty("java.io.tmpdir")),
                    env, TIMEOUT, line -> { }, () -> false));
            return result.succeeded() && !result.tail().isEmpty() ? result.tail().getLast().trim() : null;
        } catch (ProcessException e) {
            return null;
        }
    }
}
