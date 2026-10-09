package com.edgedeploy.worker.docker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Builds a container image from a checked-out repository. The local implementation uses the Docker
 * daemon on the worker host; a remote builder (BuildKit/Kaniko in an isolated sandbox) can replace it
 * without touching the pipeline.
 */
public interface DockerBuildService {

    /**
     * @param context    build context (the checked-out repository)
     * @param dockerfile generated Dockerfile, outside the context
     * @param labels     OCI labels attached to the image (deployment id, commit, source)
     * @param output     receives each line of build output, for the deployment log
     */
    record Request(ImageName image, Path context, Path dockerfile, Map<String, String> labels, Duration timeout,
                   Consumer<String> output, BooleanSupplier cancelled) {
    }

    /**
     * @param imageId      content-addressed image id (sha256:...) reported by Docker, or null
     * @param architecture CPU architecture of the image (amd64, arm64), or null if unknown; the deployment
     *                     target must run it on matching hardware
     */
    record BuiltImage(ImageName name, String imageId, String architecture) {
    }

    BuiltImage build(Request request) throws DockerBuildException;
}
