package com.edgedeploy.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FrameworkDetector {

    private final ObjectMapper objectMapper;

    public FrameworkDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String detect(Path projectDir) {
        Path packageJson = projectDir.resolve("package.json");
        if (!Files.exists(packageJson)) {
            return "unknown";
        }
        try {
            JsonNode root = objectMapper.readTree(packageJson.toFile());
            JsonNode deps = root.path("dependencies");
            JsonNode devDeps = root.path("devDependencies");
            if (has(deps, "next") || has(devDeps, "next")) {
                return "nextjs";
            }
            if ((has(deps, "react") || has(devDeps, "react")) && (has(deps, "vite") || has(devDeps, "vite"))) {
                return "react-vite";
            }
            if (has(deps, "react") || has(devDeps, "react")) {
                return "react-vite";
            }
            return "nodejs";
        } catch (Exception ex) {
            return "nodejs";
        }
    }

    private boolean has(JsonNode node, String key) {
        return node != null && node.has(key);
    }
}
