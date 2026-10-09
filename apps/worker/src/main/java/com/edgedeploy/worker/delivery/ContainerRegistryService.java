package com.edgedeploy.worker.delivery;

import com.edgedeploy.worker.docker.DockerBuildService.BuiltImage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Makes a built image available to the deployment target.
 * <ul>
 *   <li>{@link LocalContainerRegistry}: the image stays in the local daemon; deployments end at IMAGE_BUILT.</li>
 *   <li>{@code AwsEcrService}: pushes to Amazon ECR.</li>
 * </ul>
 */
public interface ContainerRegistryService {

    /** True when images must be pushed somewhere a deployment target can pull them from. */
    boolean remote();

    /**
     * @param scratchDirectory private, per-deployment directory for short-lived files (e.g. registry credentials)
     * @param progress         receives user-facing progress messages for the deployment log
     */
    record PublishRequest(UUID deploymentId, UUID projectId, BuiltImage image, Path scratchDirectory, Duration timeout,
                          BooleanSupplier cancelled, Consumer<String> progress) {
    }

    /**
     * @param imageUri        human-readable reference, {@code registry/repository:commitSha}
     * @param deployReference immutable reference the runtime should use, {@code registry/repository@sha256:...}
     * @param digest          content digest, or null when not pushed
     */
    record PublishedImage(String imageUri, String deployReference, String digest, boolean pushed) {
    }

    PublishedImage publish(PublishRequest request) throws DeliveryException;

    /** Human-readable name for logs, e.g. "Amazon ECR". */
    String description();
}
