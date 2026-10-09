package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.Framework;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the {@link BuildStrategy} for a framework and writes the generated Dockerfile into the
 * workspace's {@code build/} directory, never into the user's checked-out source.
 *
 * <p>The Dockerfile-specific ignore file ({@code Dockerfile.dockerignore}, a BuildKit feature) keeps
 * VCS metadata and local dependency folders out of the build context regardless of what the
 * repository's own {@code .dockerignore} says.
 */
@Component
public class DockerfileGenerator {

    static final List<String> IGNORED = List.of(".git", "**/.git", "node_modules", "**/node_modules", ".next");

    public record GeneratedDockerfile(Path dockerfile, Framework framework, int port, String content) {
    }

    private final Map<Framework, BuildStrategy> strategies = new EnumMap<>(Framework.class);

    public DockerfileGenerator(List<BuildStrategy> strategies) {
        strategies.forEach(strategy -> this.strategies.put(strategy.framework(), strategy));
    }

    public GeneratedDockerfile generate(BuildSettings settings, Path buildDirectory)
            throws BuildConfigurationException, IOException {
        Framework framework = settings.project().framework();
        BuildStrategy strategy = strategies.get(framework);
        if (strategy == null) {
            throw new BuildConfigurationException("No build strategy for " + framework);
        }
        String content = strategy.dockerfile(settings);
        Path dockerfile = buildDirectory.resolve("Dockerfile");
        Files.writeString(dockerfile, content);
        Files.writeString(buildDirectory.resolve("Dockerfile.dockerignore"), String.join("\n", IGNORED) + "\n");
        return new GeneratedDockerfile(dockerfile, framework, strategy.port(settings), content);
    }
}
