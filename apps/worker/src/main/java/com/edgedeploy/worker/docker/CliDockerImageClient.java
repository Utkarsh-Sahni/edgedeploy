package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.logging.SecretRedactor;
import com.edgedeploy.worker.process.ProcessException;
import com.edgedeploy.worker.process.ProcessResult;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.process.ProcessSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@link DockerImageClient} on the docker CLI.
 *
 * <p>Instead of {@code docker login} (which writes into the user's {@code ~/.docker}, possibly a system
 * keychain), the registry credential is written to {@code config.json} in a private per-deployment
 * directory, used via {@code DOCKER_CONFIG} for this one push, and deleted right after.
 */
@Component
public class CliDockerImageClient implements DockerImageClient {

    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Pattern REFERENCE = Pattern.compile("^[a-z0-9][a-z0-9._/:@-]{0,511}$");
    private static final Pattern HOST = Pattern.compile("^[a-z0-9.-]+(:\\d+)?$");

    private final ProcessRunner processes;
    private final SecretRedactor redactor;
    private final ObjectMapper json;
    private final String executable;
    private volatile String daemonHost;

    public CliDockerImageClient(ProcessRunner processes, SecretRedactor redactor, ObjectMapper json,
                                WorkerProperties properties) {
        this.processes = processes;
        this.redactor = redactor;
        this.json = json;
        this.executable = properties.docker().executable();
    }

    @Override
    public void tag(String sourceReference, String targetReference) throws DockerBuildException {
        requireReference(sourceReference);
        requireReference(targetReference);
        ProcessResult result = run(List.of(executable, "tag", sourceReference, targetReference), DockerEnvironment.forCli(),
                SHORT, () -> false, line -> { });
        if (!result.succeeded()) {
            throw new DockerBuildException(DockerBuildException.Kind.BUILD_FAILED,
                    "docker tag failed: " + redactor.redact(lastLine(result)));
        }
    }

    @Override
    public void push(String reference, RegistryCredentials credentials, Path privateConfigDirectory, Duration timeout,
                     BooleanSupplier cancelled, Consumer<String> output) throws DockerBuildException {
        requireReference(reference);
        if (!HOST.matcher(credentials.registryHost()).matches()) {
            throw new IllegalArgumentException("Invalid registry host");
        }
        String host = resolveDaemonHost();
        Path config = writeCredentials(credentials, privateConfigDirectory);
        try {
            ProcessResult result = run(List.of(executable, "push", reference),
                    DockerEnvironment.withPrivateConfig(privateConfigDirectory, host), timeout, cancelled, output);
            if (!result.succeeded()) {
                String last = redactor.redact(lastLine(result));
                String lower = result.output().toLowerCase(Locale.ROOT);
                boolean auth = lower.contains("no basic auth credentials") || lower.contains("denied")
                        || lower.contains("unauthorized") || lower.contains("authorization token has expired");
                throw new DockerBuildException(auth ? DockerBuildException.Kind.REGISTRY_AUTH : DockerBuildException.Kind.BUILD_FAILED,
                        auth ? "Registry rejected the push credentials: " + last : "docker push failed: " + last);
            }
        } finally {
            try {
                Files.deleteIfExists(config);
            } catch (IOException ignored) {
                // the whole workspace is deleted after the deployment anyway
            }
        }
    }

    private Path writeCredentials(RegistryCredentials credentials, Path directory) throws DockerBuildException {
        try {
            Files.createDirectories(directory);
            Path config = directory.resolve("config.json");
            String auth = Base64.getEncoder().encodeToString(
                    (credentials.username() + ":" + credentials.password()).getBytes(StandardCharsets.UTF_8));
            Files.writeString(config, json.writeValueAsString(Map.of("auths", Map.of(credentials.registryHost(), Map.of("auth", auth)))));
            try {
                Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            return config;
        } catch (IOException e) {
            throw new DockerBuildException(DockerBuildException.Kind.BUILD_FAILED, "Could not prepare registry credentials");
        }
    }

    /**
     * With a private DOCKER_CONFIG the CLI cannot see the user's contexts (e.g. Docker Desktop's socket), so
     * resolve the current endpoint once with the normal configuration and pass it as DOCKER_HOST.
     */
    private String resolveDaemonHost() throws DockerBuildException {
        String explicit = System.getenv("DOCKER_HOST");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (daemonHost == null) {
            ProcessResult result = run(List.of(executable, "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"),
                    DockerEnvironment.forCli(), SHORT, () -> false, line -> { });
            String host = result.succeeded() ? lastLine(result).trim() : "";
            daemonHost = host.matches("^(unix|tcp|npipe|ssh)://\\S+$") ? host : "";
        }
        return daemonHost;
    }

    private ProcessResult run(List<String> command, Map<String, String> env, Duration timeout, BooleanSupplier cancelled,
                              Consumer<String> output) throws DockerBuildException {
        try {
            return processes.run(new ProcessSpec(command, Path.of(System.getProperty("java.io.tmpdir")), env, timeout,
                    output, cancelled));
        } catch (ProcessException e) {
            throw switch (e.reason()) {
                case START_FAILED -> new DockerBuildException(DockerBuildException.Kind.DAEMON_UNAVAILABLE,
                        "The docker CLI is not installed on the build worker");
                case TIMED_OUT -> new DockerBuildException(DockerBuildException.Kind.TIMEOUT,
                        "docker " + command.get(1) + " timed out after " + timeout.toSeconds() + "s");
                case CANCELLED -> new DockerBuildException(DockerBuildException.Kind.CANCELLED, "Cancelled");
            };
        }
    }

    private static void requireReference(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("Invalid image reference");
        }
    }

    private static String lastLine(ProcessResult result) {
        return result.tail().isEmpty() ? "exit code " + result.exitCode() : result.tail().getLast();
    }
}
