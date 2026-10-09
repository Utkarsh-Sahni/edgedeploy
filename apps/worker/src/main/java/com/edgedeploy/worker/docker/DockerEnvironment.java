package com.edgedeploy.worker.docker;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The minimal environment for docker CLI processes: PATH, HOME (locates CLI contexts, e.g. Docker
 * Desktop's socket) and DOCKER_* connection settings. Nothing else from the worker's environment,
 * in particular no tokens or AWS credentials, reaches the CLI or the build.
 */
public final class DockerEnvironment {

    private static final List<String> PASSTHROUGH = List.of("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
            "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY");

    private DockerEnvironment() {
    }

    public static Map<String, String> forCli() {
        Map<String, String> env = new LinkedHashMap<>();
        Map<String, String> host = System.getenv();
        env.put("PATH", host.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        if (host.containsKey("HOME")) {
            env.put("HOME", host.get("HOME"));
        }
        PASSTHROUGH.forEach(key -> {
            if (host.containsKey(key)) {
                env.put(key, host.get(key));
            }
        });
        env.put("DOCKER_BUILDKIT", "1");
        env.put("BUILDKIT_PROGRESS", "plain");
        return env;
    }

    /**
     * Same, but reading registry credentials from a private, per-deployment config directory instead of the
     * user's {@code ~/.docker}. The daemon endpoint must then be given explicitly, because CLI contexts live in
     * the user's config directory.
     */
    public static Map<String, String> withPrivateConfig(Path configDirectory, String daemonHost) {
        Map<String, String> env = forCli();
        env.remove("DOCKER_CONTEXT");
        env.put("DOCKER_CONFIG", configDirectory.toString());
        if (daemonHost != null && !daemonHost.isBlank()) {
            env.put("DOCKER_HOST", daemonHost);
        }
        return env;
    }
}
