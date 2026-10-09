package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.logging.SecretRedactor;
import com.edgedeploy.worker.process.ProcessException;
import com.edgedeploy.worker.process.ProcessResult;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.process.ProcessSpec;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@link DockerBuildService} using the local Docker CLI and daemon (BuildKit).
 *
 * <h2>Security boundary</h2>
 * A {@code docker build} executes the repository's install and build scripts, i.e. arbitrary,
 * untrusted code. This implementation limits what that code can reach:
 * <ul>
 *   <li>the build context is only the checked-out repository; no host paths are mounted, and the
 *       generated Dockerfile uses no {@code --mount}, secrets or SSH forwarding;</li>
 *   <li>no build args, so no worker environment variable or credential enters the build;</li>
 *   <li>the docker CLI itself runs with a minimal environment (PATH, HOME, DOCKER_* only);</li>
 *   <li>commands are argument vectors, never shell strings.</li>
 * </ul>
 * It does <b>not</b> make untrusted builds safe for a multi-tenant production service: builds share
 * the host daemon (and its kernel, cache and network). Production needs isolated builders, e.g.
 * rootless BuildKit or Kaniko in per-build sandboxes (gVisor/Firecracker) with egress controls and
 * CPU/memory quotas. That swap happens behind {@link DockerBuildService}.
 */
@Service
public class CliDockerBuildService implements DockerBuildService {

    private static final Duration DAEMON_CHECK_TIMEOUT = Duration.ofSeconds(15);
    /** BuildKit plain-progress prefix, e.g. "#12 3.456 ". */
    private static final Pattern PROGRESS_PREFIX = Pattern.compile("^#\\d+\\s+(?:\\d+\\.\\d+\\s+)?");
    private static final Pattern LABEL_KEY = Pattern.compile("^[a-z0-9.-]{1,100}$");
    private final ProcessRunner processes;
    private final SecretRedactor redactor;
    private final String executable;
    private final String platform;

    public CliDockerBuildService(ProcessRunner processes, SecretRedactor redactor, WorkerProperties properties) {
        this.processes = processes;
        this.redactor = redactor;
        this.executable = properties.docker().executable();
        this.platform = properties.docker().platform() == null ? "" : properties.docker().platform().trim();
    }

    @Override
    public BuiltImage build(Request request) throws DockerBuildException {
        requireDaemon(request);

        Path iidFile = request.dockerfile().resolveSibling("image.id");
        List<String> command = buildCommand(request, iidFile);
        ProcessResult result;
        try {
            result = processes.run(new ProcessSpec(command, request.context(), DockerEnvironment.forCli(), request.timeout(),
                    request.output(), request.cancelled()));
        } catch (ProcessException e) {
            throw switch (e.reason()) {
                case START_FAILED -> new DockerBuildException(DockerBuildException.Kind.DAEMON_UNAVAILABLE,
                        "The docker CLI is not installed on the build worker");
                case TIMED_OUT -> new DockerBuildException(DockerBuildException.Kind.TIMEOUT,
                        "Docker build timed out after " + request.timeout().toSeconds() + "s");
                case CANCELLED -> new DockerBuildException(DockerBuildException.Kind.CANCELLED, "Docker build cancelled");
            };
        }
        if (!result.succeeded()) {
            throw new DockerBuildException(DockerBuildException.Kind.BUILD_FAILED, "Docker build failed: " + failureReason(result));
        }
        return new BuiltImage(request.image(), readImageId(iidFile), architecture(request));
    }

    /** {@code docker image inspect}: amd64 / arm64. Best effort: null if it cannot be determined. */
    private String architecture(Request request) {
        try {
            ProcessResult result = processes.run(new ProcessSpec(List.of(executable, "image", "inspect", "--format",
                    "{{.Architecture}}", request.image().reference()), request.context(), DockerEnvironment.forCli(),
                    DAEMON_CHECK_TIMEOUT, line -> { }, () -> false));
            String arch = result.succeeded() && !result.tail().isEmpty() ? result.tail().getLast().trim() : "";
            return arch.matches("^[a-z0-9]+$") ? arch : null;
        } catch (ProcessException e) {
            return null;
        }
    }

    /** The full {@code docker build} argument vector. Package-visible for tests. */
    List<String> buildCommand(Request request, Path iidFile) {
        List<String> command = new ArrayList<>(List.of(executable, "build",
                "--file", request.dockerfile().toString(),
                "--tag", request.image().reference(),
                "--iidfile", iidFile.toString(),
                "--progress", "plain"));
        if (!platform.isEmpty()) {
            command.add("--platform");
            command.add(platform);
        }
        request.labels().forEach((key, value) -> {
            if (!LABEL_KEY.matcher(key).matches() || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid image label " + key);
            }
            command.add("--label");
            command.add(key + "=" + value);
        });
        command.add(request.context().toString());
        return command;
    }

    private void requireDaemon(Request request) throws DockerBuildException {
        ProcessResult version;
        try {
            version = processes.run(new ProcessSpec(List.of(executable, "version", "--format", "{{.Server.Version}}"),
                    request.context(), DockerEnvironment.forCli(), DAEMON_CHECK_TIMEOUT, line -> { }, request.cancelled()));
        } catch (ProcessException e) {
            if (e.reason() == ProcessException.Reason.CANCELLED) {
                throw new DockerBuildException(DockerBuildException.Kind.CANCELLED, "Docker build cancelled");
            }
            throw new DockerBuildException(DockerBuildException.Kind.DAEMON_UNAVAILABLE,
                    e.reason() == ProcessException.Reason.START_FAILED
                            ? "The docker CLI is not installed on the build worker"
                            : "The Docker daemon did not respond");
        }
        if (!version.succeeded()) {
            throw new DockerBuildException(DockerBuildException.Kind.DAEMON_UNAVAILABLE,
                    "The Docker daemon is not available on the build worker. Is Docker running?");
        }
    }

    /**
     * The most useful line of a failed build: the last error line printed by the user's tooling
     * (e.g. "npm error 404 Not Found ..."), skipping BuildKit's generic "process ... did not complete"
     * summaries; otherwise the last line of output.
     */
    String failureReason(ProcessResult result) {
        List<String> lines = result.tail().stream().map(l -> PROGRESS_PREFIX.matcher(l).replaceFirst("").trim())
                .filter(l -> !l.isEmpty()).toList();
        String reason = null;
        for (int i = lines.size() - 1; i >= 0 && reason == null; i--) {
            String lower = lines.get(i).toLowerCase(Locale.ROOT);
            boolean buildKitSummary = lower.startsWith("error: failed to solve") || lower.startsWith("error: process");
            if ((lower.contains("err!") || lower.contains("error")) && !buildKitSummary) {
                reason = lines.get(i);
            }
        }
        if (reason == null) {
            reason = lines.isEmpty() ? "exit code " + result.exitCode() : lines.getLast();
        }
        reason = redactor.redact(reason);
        return reason.length() <= 300 ? reason : reason.substring(0, 300) + "…";
    }

    private static String readImageId(Path iidFile) {
        try {
            return Files.exists(iidFile) ? Files.readString(iidFile).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
