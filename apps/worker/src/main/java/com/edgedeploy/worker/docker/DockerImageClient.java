package com.edgedeploy.worker.docker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Moves built images between names and registries (docker tag / docker push). */
public interface DockerImageClient {

    /** Short-lived registry credentials. {@link #toString()} never shows the password. */
    record RegistryCredentials(String registryHost, String username, String password) {

        @Override
        public String toString() {
            return "RegistryCredentials[registry=" + registryHost + ", username=" + username + ", password=<redacted>]";
        }
    }

    void tag(String sourceReference, String targetReference) throws DockerBuildException;

    /**
     * Pushes an image. Credentials are written only to {@code privateConfigDirectory} (inside the deployment
     * workspace) for the duration of the push, never to the user's Docker configuration.
     */
    void push(String reference, RegistryCredentials credentials, Path privateConfigDirectory, Duration timeout,
              BooleanSupplier cancelled, Consumer<String> output) throws DockerBuildException;
}
