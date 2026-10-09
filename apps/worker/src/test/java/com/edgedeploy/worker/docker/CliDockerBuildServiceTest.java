package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.logging.SecretRedactor;
import com.edgedeploy.worker.process.ProcessException;
import com.edgedeploy.worker.process.ProcessResult;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.process.ProcessSpec;
import com.edgedeploy.worker.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Command construction and failure handling, with a scripted process runner (no Docker needed). */
class CliDockerBuildServiceTest {

    private static final String SHA = "a83f12c0a83f12c0a83f12c0a83f12c0a83f12c0";

    @TempDir
    Path workspace;

    @Test
    void buildsWithAnArgumentVectorAndAMinimalEnvironment() throws Exception {
        ScriptedRunner runner = new ScriptedRunner(spec -> spec.command().contains("version")
                ? new ProcessResult(0, List.of("27.0.0"))
                : spec.command().contains("inspect") ? new ProcessResult(0, List.of("arm64")) : writeIidAndSucceed(spec));
        DockerBuildService.BuiltImage image = service(runner).build(request(line -> { }));

        ProcessSpec build = runner.specs.get(1);
        assertThat(build.command()).containsExactly("docker", "build",
                "--file", workspace.resolve("build/Dockerfile").toString(),
                "--tag", "edgedeploy/" + PROJECT + ":" + SHA,
                "--iidfile", workspace.resolve("build/image.id").toString(),
                "--progress", "plain",
                "--label", "org.opencontainers.image.revision=" + SHA,
                workspace.resolve("source").toString());
        assertThat(build.environment()).containsKey("PATH").containsEntry("DOCKER_BUILDKIT", "1")
                .doesNotContainKey("GITHUB_DEPLOY_TOKEN").doesNotContainKey("ENCRYPTION_KEY");
        assertThat(build.environment().keySet()).allMatch(k -> k.equals("PATH") || k.equals("HOME") || k.startsWith("DOCKER_")
                || k.equals("BUILDKIT_PROGRESS"));
        assertThat(image.imageId()).isEqualTo("sha256:abc123");
        assertThat(image.architecture()).isEqualTo("arm64");
    }

    @Test
    void daemonUnavailableFailsBeforeBuilding() {
        ScriptedRunner runner = new ScriptedRunner(spec -> new ProcessResult(1,
                List.of("Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?")));

        assertThatThrownBy(() -> service(runner).build(request(line -> { })))
                .isInstanceOfSatisfying(DockerBuildException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DockerBuildException.Kind.DAEMON_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("Is Docker running?");
                });
        assertThat(runner.specs).hasSize(1);
    }

    @Test
    void failedBuildReportsTheMostUsefulErrorLine() {
        ScriptedRunner runner = new ScriptedRunner(spec -> spec.command().contains("version")
                ? new ProcessResult(0, List.of("27.0.0"))
                : new ProcessResult(1, List.of(
                "#9 [build 4/6] RUN [\"sh\",\"-c\",\"npm ci\"]",
                "#9 2.345 npm error code E404",
                "#9 2.346 npm error 404 Not Found - GET https://registry.npmjs.org/left-padd - Not found",
                "#9 ERROR: process \"sh -c npm ci\" did not complete successfully: exit code: 1",
                "ERROR: failed to solve: process \"sh -c npm ci\" did not complete successfully: exit code: 1")));

        assertThatThrownBy(() -> service(runner).build(request(line -> { })))
                .isInstanceOfSatisfying(DockerBuildException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DockerBuildException.Kind.BUILD_FAILED);
                    assertThat(e.getMessage()).isEqualTo("Docker build failed: npm error 404 Not Found - GET "
                            + "https://registry.npmjs.org/left-padd - Not found");
                });
    }

    @Test
    void failureReasonIsRedacted() {
        CliDockerBuildService service = service(new ScriptedRunner(spec -> null));
        String reason = service.failureReason(new ProcessResult(1, List.of("npm error token " + TestProperties.DEPLOY_TOKEN + " invalid")));

        assertThat(reason).doesNotContain(TestProperties.DEPLOY_TOKEN).contains("[REDACTED]");
    }

    @Test
    void timeoutAndCancellationAreDistinguished() {
        ScriptedRunner timesOut = new ScriptedRunner(spec -> {
            if (spec.command().contains("version")) {
                return new ProcessResult(0, List.of("27"));
            }
            throw new Thrown(new ProcessException(ProcessException.Reason.TIMED_OUT, "t", null));
        });
        ScriptedRunner cancelled = new ScriptedRunner(spec -> {
            if (spec.command().contains("version")) {
                return new ProcessResult(0, List.of("27"));
            }
            throw new Thrown(new ProcessException(ProcessException.Reason.CANCELLED, "c", null));
        });

        assertThatThrownBy(() -> service(timesOut).build(request(l -> { })))
                .isInstanceOfSatisfying(DockerBuildException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DockerBuildException.Kind.TIMEOUT);
                    assertThat(e.getMessage()).contains("timed out after 120s");
                });
        assertThatThrownBy(() -> service(cancelled).build(request(l -> { })))
                .isInstanceOfSatisfying(DockerBuildException.class, e ->
                        assertThat(e.kind()).isEqualTo(DockerBuildException.Kind.CANCELLED));
    }

    @Test
    void streamsBuildOutputToTheDeploymentLog() throws Exception {
        List<String> logged = new ArrayList<>();
        ScriptedRunner runner = new ScriptedRunner(spec -> {
            if (spec.command().contains("inspect")) {
                return new ProcessResult(0, List.of("amd64"));
            }
            if (!spec.command().contains("version")) {
                spec.onOutput().accept("#5 [build 1/5] FROM node:22-alpine");
                return writeIidAndSucceed(spec);
            }
            return new ProcessResult(0, List.of("27"));
        });

        service(runner).build(request(logged::add));

        assertThat(logged).containsExactly("#5 [build 1/5] FROM node:22-alpine");
    }

    private static final UUID PROJECT = UUID.fromString("8f42d1a0-0000-4000-8000-000000000042");

    private DockerBuildService.Request request(java.util.function.Consumer<String> output) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("org.opencontainers.image.revision", SHA);
        return new DockerBuildService.Request(ImageName.of("edgedeploy", PROJECT, SHA), workspace.resolve("source"),
                workspace.resolve("build/Dockerfile"), labels, Duration.ofSeconds(120), output, () -> false);
    }

    private CliDockerBuildService service(ProcessRunner runner) {
        var properties = TestProperties.create(workspace, "https://github.com");
        return new CliDockerBuildService(runner, new SecretRedactor(properties), properties);
    }

    private ProcessResult writeIidAndSucceed(ProcessSpec spec) {
        try {
            int index = spec.command().indexOf("--iidfile");
            Path iid = Path.of(spec.command().get(index + 1));
            Files.createDirectories(iid.getParent());
            Files.writeString(iid, "sha256:abc123\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ProcessResult(0, List.of("naming to docker.io/edgedeploy/... done"));
    }

    /** Wraps a checked ProcessException so it can be thrown from a lambda. */
    private static final class Thrown extends RuntimeException {
        final ProcessException cause;

        Thrown(ProcessException cause) {
            this.cause = cause;
        }
    }

    private static final class ScriptedRunner extends ProcessRunner {
        final List<ProcessSpec> specs = new ArrayList<>();
        private final Function<ProcessSpec, ProcessResult> script;

        ScriptedRunner(Function<ProcessSpec, ProcessResult> script) {
            this.script = script;
        }

        @Override
        public ProcessResult run(ProcessSpec spec) throws ProcessException {
            specs.add(spec);
            try {
                return script.apply(spec);
            } catch (Thrown t) {
                throw t.cause;
            }
        }
    }
}
