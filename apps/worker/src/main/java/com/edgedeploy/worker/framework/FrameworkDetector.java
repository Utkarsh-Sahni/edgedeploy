package com.edgedeploy.worker.framework;

import com.edgedeploy.worker.workspace.SafeFiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Works out what kind of JavaScript project a repository is, from its root {@code package.json} and
 * config files. Deliberately narrow: Next.js, Vite, Create React App and plain Node.js. Anything else
 * fails with an actionable message instead of a guess.
 *
 * <p>Repository files are untrusted: reads are size-bounded and never follow symbolic links.
 */
@Component
public class FrameworkDetector {

    private static final int MAX_PACKAGE_JSON_BYTES = 1024 * 1024;
    private static final List<String> NEXT_CONFIGS = List.of("next.config.js", "next.config.mjs", "next.config.cjs", "next.config.ts");
    private static final List<String> VITE_CONFIGS = List.of("vite.config.js", "vite.config.mjs", "vite.config.cjs",
            "vite.config.ts", "vite.config.mts", "vite.config.cts");
    /** Extra root files that influence dependency installation and must be copied before installing. */
    private static final List<String> INSTALL_CONFIG_FILES = List.of(".npmrc", ".yarnrc", ".yarnrc.yml", "pnpm-workspace.yaml");

    private final ObjectMapper objectMapper;

    public FrameworkDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param configured framework chosen in project settings (REACT, VITE, NEXTJS, NODE), or null/UNKNOWN to detect
     */
    public DetectedProject detect(Path sourceDir, String configured) throws FrameworkDetectionException {
        JsonNode packageJson = readPackageJson(sourceDir);
        Set<String> dependencies = names(packageJson.path("dependencies"));
        dependencies.addAll(names(packageJson.path("devDependencies")));
        Set<String> scripts = names(packageJson.path("scripts"));
        String main = packageJson.path("main").isTextual() ? packageJson.path("main").asText() : null;

        Framework framework;
        String reason;
        Optional<Framework> override = parseConfigured(configured);
        if (override.isPresent()) {
            framework = override.get();
            reason = "set in project settings";
        } else if (dependencies.contains("next") || anyExists(sourceDir, NEXT_CONFIGS)) {
            framework = Framework.NEXTJS;
            reason = dependencies.contains("next") ? "\"next\" dependency" : "next.config file";
        } else if (dependencies.contains("vite") || anyExists(sourceDir, VITE_CONFIGS)) {
            framework = Framework.VITE;
            reason = dependencies.contains("vite") ? "\"vite\" dependency" : "vite.config file";
        } else if (dependencies.contains("react-scripts")) {
            framework = Framework.REACT;
            reason = "\"react-scripts\" dependency (Create React App)";
        } else if (scripts.contains("start") || main != null) {
            framework = Framework.NODE;
            reason = scripts.contains("start") ? "\"start\" script" : "\"main\" entry";
        } else {
            throw new FrameworkDetectionException("Could not detect a supported framework. EdgeDeploy builds Next.js, "
                    + "Vite, Create React App and Node.js projects; add a \"start\" script for a Node.js server, or "
                    + "choose the framework in project settings.");
        }

        PackageManager packageManager = detectPackageManager(sourceDir, packageJson);
        boolean hasLockfile = SafeFiles.isRegularFile(sourceDir, packageManager.lockfile());
        List<String> manifests = new ArrayList<>(List.of("package.json"));
        if (hasLockfile) {
            manifests.add(packageManager.lockfile());
        }
        INSTALL_CONFIG_FILES.stream().filter(f -> SafeFiles.isRegularFile(sourceDir, f)).forEach(manifests::add);

        return new DetectedProject(framework, packageManager, Set.copyOf(scripts), main, hasLockfile,
                List.copyOf(manifests), reason);
    }

    private JsonNode readPackageJson(Path sourceDir) throws FrameworkDetectionException {
        Optional<String> content;
        try {
            content = SafeFiles.readRegularFile(sourceDir, "package.json", MAX_PACKAGE_JSON_BYTES);
        } catch (IOException e) {
            throw new FrameworkDetectionException("package.json could not be read: " + e.getMessage());
        }
        if (content.isEmpty()) {
            throw new FrameworkDetectionException("No package.json found at the repository root. EdgeDeploy builds "
                    + "JavaScript projects (Next.js, Vite, Create React App, Node.js).");
        }
        try {
            JsonNode node = objectMapper.readTree(content.get());
            if (node == null || !node.isObject()) {
                throw new FrameworkDetectionException("package.json must contain a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new FrameworkDetectionException("package.json is not valid JSON (line "
                    + (e.getLocation() != null ? e.getLocation().getLineNr() : "?") + ")");
        }
    }

    /**
     * Precedence: the {@code packageManager} field (Corepack) > lockfiles > npm. Lockfiles are checked
     * pnpm, then Yarn, then npm, matching how projects usually migrate.
     */
    private static PackageManager detectPackageManager(Path sourceDir, JsonNode packageJson) {
        String declared = packageJson.path("packageManager").asText("").toLowerCase(Locale.ROOT);
        boolean berry = SafeFiles.isRegularFile(sourceDir, ".yarnrc.yml");
        if (declared.startsWith("pnpm@")) {
            return PackageManager.PNPM;
        }
        if (declared.startsWith("yarn@")) {
            return declared.startsWith("yarn@1.") && !berry ? PackageManager.YARN : PackageManager.YARN_BERRY;
        }
        if (declared.startsWith("npm@")) {
            return PackageManager.NPM;
        }
        if (SafeFiles.isRegularFile(sourceDir, "pnpm-lock.yaml")) {
            return PackageManager.PNPM;
        }
        if (SafeFiles.isRegularFile(sourceDir, "yarn.lock")) {
            return berry ? PackageManager.YARN_BERRY : PackageManager.YARN;
        }
        return PackageManager.NPM;
    }

    private static Optional<Framework> parseConfigured(String configured) {
        if (configured == null || configured.isBlank() || configured.equals("UNKNOWN")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Framework.valueOf(configured));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean anyExists(Path dir, List<String> names) {
        return names.stream().anyMatch(name -> SafeFiles.isRegularFile(dir, name));
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new HashSet<>();
        if (object.isObject()) {
            for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
        }
        return names;
    }
}
