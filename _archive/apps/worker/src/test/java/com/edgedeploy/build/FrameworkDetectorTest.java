package com.edgedeploy.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FrameworkDetectorTest {

    private final FrameworkDetector detector = new FrameworkDetector(new ObjectMapper());

    @TempDir
    Path tempDir;

    @Test
    void detectsNextJs() throws Exception {
        Files.writeString(tempDir.resolve("package.json"), """
                {"dependencies":{"next":"15.0.0","react":"19.0.0"}}
                """);
        assertThat(detector.detect(tempDir)).isEqualTo("nextjs");
    }

    @Test
    void detectsViteReact() throws Exception {
        Files.writeString(tempDir.resolve("package.json"), """
                {"dependencies":{"react":"18.3.1"},"devDependencies":{"vite":"5.4.0"}}
                """);
        assertThat(detector.detect(tempDir)).isEqualTo("react-vite");
    }
}
