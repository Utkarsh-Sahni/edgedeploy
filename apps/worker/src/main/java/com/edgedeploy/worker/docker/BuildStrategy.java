package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.Framework;

/**
 * How one framework is containerised: base image, install/build commands, output and runtime.
 * Implementations render a complete multi-stage Dockerfile.
 */
public interface BuildStrategy {

    Framework framework();

    /** Port the container listens on; the deployment target routes traffic to it. */
    int port(BuildSettings settings);

    /** @throws BuildConfigurationException if the project lacks something this framework needs (e.g. a build script) */
    String dockerfile(BuildSettings settings) throws BuildConfigurationException;
}
