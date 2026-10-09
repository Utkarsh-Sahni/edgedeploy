package com.edgedeploy.worker.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs a published image somewhere reachable. The pipeline drives the steps (and the deployment status
 * between them); implementations only talk to their platform.
 * <ul>
 *   <li>{@link NoDeploymentTarget}: local mode, nothing is deployed.</li>
 *   <li>{@code AwsEcsService}: ECS Fargate, behind an Application Load Balancer or (budget mode) on the task's public IP.</li>
 * </ul>
 * Kubernetes, Cloud Run or Azure Container Apps would be further implementations of this interface.
 */
public interface DeploymentTargetService {

    /** Whether this target actually deploys; decides if the pipeline continues past the image. */
    boolean enabled();

    /**
     * What to run. {@code environment} holds decrypted values: never log it ({@link #toString()} hides them).
     */
    record Release(UUID deploymentId, UUID projectId, int deploymentNumber, String commitSha, String imageReference,
                   String imageArchitecture, int containerPort, Map<String, String> environment) {

        @Override
        public String toString() {
            return "Release[deployment=" + deploymentId + ", project=" + projectId + ", image=" + imageReference
                    + ", port=" + containerPort + ", environment=" + environment.size() + " variable(s)]";
        }
    }

    /**
     * A started rollout: identifiers kept on the deployment so it can be rolled back to later.
     *
     * @param url            public address of the application, or null until a task runs (budget mode: the
     *                       address is the task's own public IP)
     * @param targetGroupArn load balancer target group, or null without a load balancer
     */
    record Rollout(String cluster, String service, String taskDefinitionArn, String url, String targetGroupArn,
                   int containerPort, Instant startedAt) {

        public Rollout withUrl(String newUrl) {
            return new Rollout(cluster, service, taskDefinitionArn, newUrl, targetGroupArn, containerPort, startedAt);
        }
    }

    /**
     * The new version is up.
     *
     * @param instance identifier of a running instance (ECS: task ARN)
     * @param url      public address of the application
     */
    record Running(String instance, String url) {
    }

    /** Registers the new version and points the running service at it (ECS: task definition + service update). */
    Rollout start(Release release, Consumer<String> progress) throws DeliveryException;

    /** Waits until the new version fully replaced the old one, and reports where it can be reached. */
    Running awaitStable(Rollout rollout, Duration timeout, Consumer<String> progress) throws DeliveryException;

    /** Confirms the application answers through its public address. */
    void verifyHealth(Rollout rollout, Duration timeout, Consumer<String> progress) throws DeliveryException;

    /**
     * Best effort, after a failed rollout: return the service to its last good version, or stop it when there
     * is none (so a broken first deployment does not keep restarting and costing money). Never throws.
     *
     * @param previousVersion the last good version's identifier (ECS: task definition ARN), or null
     */
    void restore(Rollout rollout, String previousVersion, Consumer<String> progress);
}
