package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.framework.DetectedProject;
import com.edgedeploy.worker.framework.Framework;
import com.edgedeploy.worker.framework.FrameworkDetector;
import com.edgedeploy.worker.framework.PackageManager;
import com.edgedeploy.worker.logging.SecretRedactor;
import com.edgedeploy.worker.process.ProcessRunner;
import com.edgedeploy.worker.process.ProcessSpec;
import com.edgedeploy.worker.support.TestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real {@code docker build} of {@code test-fixtures/sample-vite-app}: detection, Dockerfile generation and
 * BuildKit, end to end. Needs a running Docker daemon and network access (base images, npm), so it only
 * runs when asked:
 *
 * <pre>EDGEDEPLOY_DOCKER_E2E=true ./mvnw -pl apps/worker -am test -Dtest=DockerBuildE2ETest -Dsurefire.failIfNoSpecifiedTests=false</pre>
 */
@EnabledIfEnvironmentVariable(named = "EDGEDEPLOY_DOCKER_E2E", matches = "true")
class DockerBuildE2ETest {

    private static final Path SAMPLE = Path.of("../../test-fixtures/sample-vite-app");

    @TempDir
    Path workspace;

    @Test
    void buildsTheSampleViteAppIntoAServableImage() throws Exception {
        Path source = Files.createDirectories(workspace.resolve("source"));
        Path build = Files.createDirectories(workspace.resolve("build"));
        copyTree(SAMPLE, source);

        DetectedProject project = new FrameworkDetector(new ObjectMapper()).detect(source, null);
        assertThat(project.framework()).isEqualTo(Framework.VITE);
        assertThat(project.packageManager()).isEqualTo(PackageManager.NPM);

        WorkerProperties properties = TestProperties.create(workspace, "https://github.com");
        DockerfileGenerator.GeneratedDockerfile dockerfile = new DockerfileGenerator(List.of(new ViteBuildStrategy()))
                .generate(new BuildSettings(project, null, null, properties.docker().nodeImage(),
                        properties.docker().staticRuntimeImage(), properties.docker().appPort()), build);

        ProcessRunner runner = new ProcessRunner();
        ImageName image = ImageName.of("edgedeploy-e2e", UUID.randomUUID(), "a2a0000000000000000000000000000000000000");
        List<String> output = new CopyOnWriteArrayList<>();
        try {
            DockerBuildService.BuiltImage built = new CliDockerBuildService(runner, new SecretRedactor(properties), properties)
                    .build(new DockerBuildService.Request(image, source, dockerfile.dockerfile(),
                            Map.of("dev.edgedeploy.test", "e2e"), Duration.ofMinutes(10), output::add, () -> false));

            assertThat(built.imageId()).startsWith("sha256:");
            assertThat(output).anyMatch(line -> line.contains("npm ci"));
            // The runtime image holds the compiled site, not the sources.
            var listing = runner.run(new ProcessSpec(List.of("docker", "run", "--rm", "--entrypoint", "ls", image.reference(),
                    "/usr/share/nginx/html"), workspace, dockerEnv(), Duration.ofMinutes(2), l -> { }, () -> false));
            assertThat(listing.tail()).contains("index.html", "assets");
        } finally {
            runner.run(new ProcessSpec(List.of("docker", "image", "rm", "-f", image.reference()), workspace, dockerEnv(),
                    Duration.ofMinutes(1), l -> { }, () -> false));
        }
    }

    private static Map<String, String> dockerEnv() {
        Map<String, String> env = new java.util.HashMap<>();
        System.getenv().forEach((k, v) -> {
            if (k.equals("PATH") || k.equals("HOME") || k.startsWith("DOCKER_")) {
                env.put(k, v);
            }
        });
        return env;
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.filter(p -> !p.toString().contains("node_modules")).toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
