package com.edgedeploy.worker.framework;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameworkDetectorTest {

    @TempDir
    Path repo;

    private final FrameworkDetector detector = new FrameworkDetector(new ObjectMapper());

    @Test
    void detectsViteWithPnpm() throws Exception {
        write("package.json", """
                {"scripts":{"build":"vite build"},"dependencies":{"react":"19.0.0"},"devDependencies":{"vite":"6.0.0"}}
                """);
        write("pnpm-lock.yaml", "lockfileVersion: '9.0'");

        DetectedProject project = detector.detect(repo, "UNKNOWN");

        assertThat(project.framework()).isEqualTo(Framework.VITE);
        assertThat(project.packageManager()).isEqualTo(PackageManager.PNPM);
        assertThat(project.hasLockfile()).isTrue();
        assertThat(project.manifestFiles()).containsExactly("package.json", "pnpm-lock.yaml");
    }

    @Test
    void detectsViteFromConfigFileAlone() throws Exception {
        write("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        write("vite.config.ts", "export default {}");

        assertThat(detector.detect(repo, null).framework()).isEqualTo(Framework.VITE);
    }

    @Test
    void detectsCreateReactAppWithYarn() throws Exception {
        write("package.json", "{\"scripts\":{\"build\":\"react-scripts build\"},\"dependencies\":{\"react-scripts\":\"5.0.1\"}}");
        write("yarn.lock", "# yarn lockfile v1");

        DetectedProject project = detector.detect(repo, null);

        assertThat(project.framework()).isEqualTo(Framework.REACT);
        assertThat(project.packageManager()).isEqualTo(PackageManager.YARN);
    }

    @Test
    void detectsNextJsWhichWinsOverReact() throws Exception {
        write("package.json", "{\"dependencies\":{\"next\":\"15.0.0\",\"react\":\"19.0.0\"},\"scripts\":{\"build\":\"next build\"}}");
        write("package-lock.json", "{}");

        DetectedProject project = detector.detect(repo, null);

        assertThat(project.framework()).isEqualTo(Framework.NEXTJS);
        assertThat(project.packageManager()).isEqualTo(PackageManager.NPM);
    }

    @Test
    void detectsNodeServerFromStartScript() throws Exception {
        write("package.json", "{\"scripts\":{\"start\":\"node server.js\"},\"dependencies\":{\"express\":\"5.0.0\"}}");

        DetectedProject project = detector.detect(repo, null);

        assertThat(project.framework()).isEqualTo(Framework.NODE);
        assertThat(project.hasLockfile()).isFalse();
    }

    @Test
    void packageManagerFieldBeatsLockfiles() throws Exception {
        write("package.json", "{\"packageManager\":\"yarn@4.5.0\",\"scripts\":{\"start\":\"node .\"}}");
        write("package-lock.json", "{}");

        assertThat(detector.detect(repo, null).packageManager()).isEqualTo(PackageManager.YARN_BERRY);
    }

    @Test
    void projectSettingOverridesDetection() throws Exception {
        write("package.json", "{\"dependencies\":{\"vite\":\"6.0.0\"},\"scripts\":{\"start\":\"node server.js\"}}");

        assertThat(detector.detect(repo, "NODE").framework()).isEqualTo(Framework.NODE);
    }

    @Test
    void missingPackageJsonFailsWithActionableMessage() {
        assertThatThrownBy(() -> detector.detect(repo, null))
                .isInstanceOf(FrameworkDetectionException.class)
                .hasMessageContaining("No package.json");
    }

    @Test
    void unsupportedProjectFails() throws Exception {
        write("package.json", "{\"name\":\"just-a-library\",\"dependencies\":{\"lodash\":\"4.0.0\"}}");

        assertThatThrownBy(() -> detector.detect(repo, null))
                .isInstanceOf(FrameworkDetectionException.class)
                .hasMessageContaining("Could not detect a supported framework");
    }

    @Test
    void invalidJsonFails() throws Exception {
        write("package.json", "{ not json");

        assertThatThrownBy(() -> detector.detect(repo, null))
                .isInstanceOf(FrameworkDetectionException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void symlinkedPackageJsonIsNotFollowed() throws Exception {
        Path outside = Files.createTempFile("host", ".json");
        Files.writeString(outside, "{\"scripts\":{\"start\":\"node .\"}}");
        Files.createSymbolicLink(repo.resolve("package.json"), outside);

        assertThatThrownBy(() -> detector.detect(repo, null))
                .isInstanceOf(FrameworkDetectionException.class)
                .hasMessageContaining("No package.json");
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(repo.resolve(name), content);
    }
}
