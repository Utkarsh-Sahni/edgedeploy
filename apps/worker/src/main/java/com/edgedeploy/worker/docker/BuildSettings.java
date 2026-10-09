package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.DetectedProject;

/**
 * Inputs for generating a Dockerfile.
 *
 * @param buildCommand project override for the build command, or null for the framework default
 * @param startCommand project override for the start command, or null for the framework default
 * @param appPort      port server frameworks (Node.js, Next.js) listen on, exported as PORT
 */
public record BuildSettings(DetectedProject project, String buildCommand, String startCommand,
                            String nodeImage, String staticRuntimeImage, int appPort) {
}
