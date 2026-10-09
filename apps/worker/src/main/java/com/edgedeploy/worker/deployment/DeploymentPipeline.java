package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.delivery.ContainerRegistryService;
import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.delivery.DeploymentTargetService;
import com.edgedeploy.worker.docker.BuildConfigurationException;
import com.edgedeploy.worker.docker.BuildSettings;
import com.edgedeploy.worker.docker.DockerBuildException;
import com.edgedeploy.worker.docker.DockerBuildService;
import com.edgedeploy.worker.docker.DockerfileGenerator;
import com.edgedeploy.worker.docker.ImageName;
import com.edgedeploy.worker.framework.DetectedProject;
import com.edgedeploy.worker.framework.FrameworkDetectionException;
import com.edgedeploy.worker.framework.FrameworkDetector;
import com.edgedeploy.worker.github.GitException;
import com.edgedeploy.worker.github.GitService;
import com.edgedeploy.worker.logging.BuildOutputSink;
import com.edgedeploy.worker.logging.DeploymentLogService;
import com.edgedeploy.worker.service.DeploymentStatusService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The deployment steps, in order. Each step delegates to one integration and translates its failures
 * into a user-facing {@link DeploymentFailure} attributed to a timeline step.
 *
 * <pre>
 * BUILDING:     clone -> checkout + verify commit -> detect framework -> generate Dockerfile -> docker build
 * PUSHING:      registry.publish                         (ECR: ensure repository, authenticate, tag, push)
 * DEPLOYING:    target.start + target.awaitStable         (ECS: task definition revision, service update, rollout)
 * HEALTH_CHECK: target.verifyHealth                       (ALB target health if any + HTTP through the public URL)
 * RUNNING
 * </pre>
 * In local mode (no remote registry) the pipeline ends at IMAGE_BUILT after the build. The pipeline only sees
 * the {@link ContainerRegistryService} and {@link DeploymentTargetService} interfaces, never an AWS SDK type,
 * so another platform (Kubernetes, Cloud Run...) is a new implementation, not a new pipeline.
 */
@Component
public class DeploymentPipeline {

    private final GitService git;
    private final FrameworkDetector detector;
    private final DockerfileGenerator dockerfiles;
    private final DockerBuildService docker;
    private final ContainerRegistryService registry;
    private final DeploymentTargetService target;
    private final DeploymentLogService logs;
    private final DeploymentStatusService status;
    private final EnvironmentVariableReader environment;
    private final ProjectDeploymentLock locks;
    private final WorkerProperties properties;

    public DeploymentPipeline(GitService git, FrameworkDetector detector, DockerfileGenerator dockerfiles,
                              DockerBuildService docker, ContainerRegistryService registry,
                              DeploymentTargetService target, DeploymentLogService logs,
                              DeploymentStatusService status, EnvironmentVariableReader environment,
                              ProjectDeploymentLock locks, WorkerProperties properties) {
        this.environment = environment;
        this.locks = locks;
        this.git = git;
        this.detector = detector;
        this.dockerfiles = dockerfiles;
        this.docker = docker;
        this.registry = registry;
        this.target = target;
        this.logs = logs;
        this.status = status;
        this.properties = properties;
    }

    public void run(DeploymentContext context) throws DeploymentFailure, DeploymentCancelledException {
        UUID id = context.deploymentId();
        ClaimedDeployment deployment = context.deployment();

        // 1. Clone
        context.enter(DeploymentStep.CLONE);
        logs.info(id, "Cloning " + deployment.repository() + " (branch " + deployment.branch() + ")");
        try {
            git.cloneRepository(gitRequest(context));
        } catch (GitException e) {
            throw gitFailure(context, e);
        }
        logs.step(id, DeploymentStep.CLONE, "Repository cloned: " + deployment.repository());

        // 2. Checkout and verify the exact commit
        context.enter(DeploymentStep.COMMIT);
        logs.info(id, "Checking out commit " + shortSha(deployment.commitSha() != null ? deployment.commitSha() : deployment.branch()));
        String head;
        try {
            head = git.checkoutCommit(gitRequest(context));
        } catch (GitException e) {
            throw gitFailure(context, e);
        }
        if (deployment.commitSha() == null) {
            status.recordResolvedCommit(id, head);
        }
        context.commitSha(head);
        logs.step(id, DeploymentStep.COMMIT, "Commit " + shortSha(head) + " verified (HEAD matches the requested commit)");

        // 3. Detect framework and package manager
        context.enter(DeploymentStep.FRAMEWORK);
        DetectedProject project;
        try {
            project = detector.detect(context.workspace().source(), deployment.framework());
        } catch (FrameworkDetectionException e) {
            throw new DeploymentFailure(DeploymentStep.FRAMEWORK, "Framework detection failed: " + e.getMessage());
        }
        logs.step(id, DeploymentStep.FRAMEWORK, "Detected framework: " + project.framework() + " (" + project.reason() + ")");
        logs.info(id, "Detected package manager: " + project.packageManager()
                + (project.hasLockfile() ? " (" + project.packageManager().lockfile() + ")" : " (no lockfile: dependency versions are not pinned)"));

        // 4. Generate the Dockerfile (outside the user's source tree)
        context.enter(DeploymentStep.IMAGE);
        WorkerProperties.Docker dockerConfig = properties.docker();
        logs.info(id, "Generating Dockerfile (base image " + dockerConfig.nodeImage() + ")");
        DockerfileGenerator.GeneratedDockerfile dockerfile;
        try {
            dockerfile = dockerfiles.generate(new BuildSettings(project, deployment.buildCommand(), deployment.startCommand(),
                    dockerConfig.nodeImage(), dockerConfig.staticRuntimeImage(), dockerConfig.appPort()),
                    context.workspace().build());
        } catch (BuildConfigurationException e) {
            throw new DeploymentFailure(DeploymentStep.IMAGE, e.getMessage());
        } catch (IOException e) {
            throw new DeploymentFailure(DeploymentStep.IMAGE, "Could not write the generated Dockerfile");
        }

        // 5. Build the image
        ImageName image = ImageName.of(dockerConfig.imageRepository(), context.projectId(), head);
        logs.info(id, "Building Docker image " + image);
        DockerBuildService.BuiltImage built;
        try (BuildOutputSink output = logs.outputSink(id)) {
            built = docker.build(new DockerBuildService.Request(image, context.workspace().source(), dockerfile.dockerfile(),
                    labels(context, head), context.timeoutFor(properties.timeouts().dockerBuild()), output, context.cancelled()));
        } catch (DockerBuildException e) {
            if (e.kind() == DockerBuildException.Kind.CANCELLED) {
                throw new DeploymentCancelledException();
            }
            if (e.kind() == DockerBuildException.Kind.TIMEOUT && context.pastDeadline()) {
                throw context.timedOut();
            }
            throw new DeploymentFailure(DeploymentStep.IMAGE, e.getMessage());
        }
        logs.info(id, "Docker build completed" + (built.imageId() != null ? " (image id " + shortImageId(built.imageId()) + ")" : ""));

        if (!registry.remote()) {
            // Local mode: the image stays in the worker's daemon and the pipeline ends here.
            logs.info(id, "Image available in " + registry.description() + ": " + image.reference());
            logs.info(id, "No deployment target configured (EDGEDEPLOY_AWS_ENABLED=false); finishing at IMAGE_BUILT.");
            status.imageBuilt(id, context.projectId(), image.reference(), true);
            context.status(DeploymentStatus.IMAGE_BUILT);
            return;
        }
        logs.step(id, DeploymentStep.IMAGE, "Docker image built: " + image.reference());
        if (!target.enabled()) {
            throw new IllegalStateException("A remote registry is configured without a deployment target");
        }

        ContainerRegistryService.PublishedImage published = push(context, built);
        deploy(context, published, built, dockerfile.port());
    }

    /** 6. PUSHING: make the image available to the deployment target. */
    private ContainerRegistryService.PublishedImage push(DeploymentContext context, DockerBuildService.BuiltImage built)
            throws DeploymentFailure, DeploymentCancelledException {
        UUID id = context.deploymentId();
        context.enter(DeploymentStep.PUSH);
        status.transition(id, context.projectId(), DeploymentStatus.BUILDING, DeploymentStatus.PUSHING,
                "Pushing image to " + registry.description());
        context.status(DeploymentStatus.PUSHING);
        ContainerRegistryService.PublishedImage published;
        try {
            published = registry.publish(new ContainerRegistryService.PublishRequest(id, context.projectId(), built,
                    context.workspace().root().resolve("registry"), context.timeoutFor(properties.timeouts().imagePush()),
                    context.cancelled(), progress(id)));
        } catch (DeliveryException e) {
            throw deliveryFailure(context, e);
        }
        status.imagePushed(id, published.imageUri(), published.digest());
        return published;
    }

    /**
     * 7-9. DEPLOYING -> HEALTH_CHECK -> RUNNING. Holds the project's deployment lock, so two deployments of one
     * project never roll out at the same time. Once the target has been changed, any failure restores the
     * previously running version (or stops a first deployment that never worked).
     */
    private void deploy(DeploymentContext context, ContainerRegistryService.PublishedImage published,
                        DockerBuildService.BuiltImage built, int containerPort)
            throws DeploymentFailure, DeploymentCancelledException {
        UUID id = context.deploymentId();
        UUID projectId = context.projectId();
        context.enter(DeploymentStep.DEPLOY);

        Map<String, String> variables;
        try {
            variables = environment.read(projectId);
        } catch (EnvironmentVariableReader.DeploymentConfigurationException e) {
            throw new DeploymentFailure(DeploymentStep.DEPLOY, e.getMessage());
        }

        Optional<ProjectDeploymentLock.Handle> acquired;
        try {
            acquired = locks.acquire(projectId, context.timeoutFor(properties.timeouts().deployment()), progress(id));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeploymentCancelledException();
        }
        if (acquired.isEmpty()) {
            throw new DeploymentFailure(DeploymentStep.DEPLOY, "Another deployment of this project did not finish in time");
        }
        try (ProjectDeploymentLock.Handle lock = acquired.get()) {
            Optional<Integer> newer = status.newerDeploymentStarted(projectId, context.deployment().number());
            if (newer.isPresent()) {
                status.stopSuperseded(id, projectId, DeploymentStatus.PUSHING, newer.get());
                context.status(DeploymentStatus.STOPPED);
                return;
            }
            context.enter(DeploymentStep.DEPLOY); // re-check cancellation and time after waiting for the lock
            status.transition(id, projectId, DeploymentStatus.PUSHING, DeploymentStatus.DEPLOYING, "Deploying the new version");
            context.status(DeploymentStatus.DEPLOYING);

            String previousVersion = status.runningVersion(projectId, id).orElse(null);
            DeploymentTargetService.Rollout rollout;
            try {
                rollout = target.start(new DeploymentTargetService.Release(id, projectId, context.deployment().number(),
                        context.commitSha(), published.deployReference(), built.architecture(), containerPort, variables),
                        progress(id));
            } catch (DeliveryException e) {
                throw deliveryFailure(context, e);
            }
            status.recordRollout(id, rollout.cluster(), rollout.service(), rollout.taskDefinitionArn());

            try {
                DeploymentTargetService.Running running = target.awaitStable(rollout,
                        context.timeoutFor(properties.timeouts().rollout()), progress(id));
                rollout = rollout.withUrl(running.url());
                logs.step(id, DeploymentStep.DEPLOY, "Deployment rolled out");

                context.enter(DeploymentStep.HEALTH);
                status.transition(id, projectId, DeploymentStatus.DEPLOYING, DeploymentStatus.HEALTH_CHECK, "Running health checks");
                context.status(DeploymentStatus.HEALTH_CHECK);
                target.verifyHealth(rollout, context.timeoutFor(properties.timeouts().healthCheck()), progress(id));
                logs.step(id, DeploymentStep.HEALTH, "Health check passed");

                status.markRunning(id, projectId, context.deployment().number(), rollout.url(), running.instance());
                context.status(DeploymentStatus.RUNNING);
            } catch (DeliveryException e) {
                target.restore(rollout, previousVersion, progress(id));
                throw deliveryFailure(context, e);
            } catch (DeploymentFailure | DeploymentCancelledException | RuntimeException e) {
                target.restore(rollout, previousVersion, progress(id));
                throw e;
            }
        }
    }

    private DeploymentFailure deliveryFailure(DeploymentContext context, DeliveryException e) throws DeploymentCancelledException {
        if (context.cancelled().getAsBoolean()) {
            throw new DeploymentCancelledException();
        }
        if (context.pastDeadline()) {
            return context.timedOut();
        }
        return new DeploymentFailure(context.step(), e.getMessage());
    }

    private Consumer<String> progress(UUID deploymentId) {
        return message -> logs.info(deploymentId, message);
    }

    private GitService.Request gitRequest(DeploymentContext context) {
        ClaimedDeployment d = context.deployment();
        return new GitService.Request(d.repository(), d.branch(), d.commitSha(), context.workspace(),
                context.timeoutFor(properties.timeouts().gitOperation()), context.cancelled());
    }

    private static DeploymentFailure gitFailure(DeploymentContext context, GitException e) throws DeploymentCancelledException {
        if (e.kind() == GitException.Kind.CANCELLED) {
            throw new DeploymentCancelledException();
        }
        if (e.kind() == GitException.Kind.TIMEOUT && context.pastDeadline()) {
            return context.timedOut();
        }
        DeploymentStep step = switch (e.kind()) {
            case COMMIT_NOT_FOUND, COMMIT_MISMATCH -> DeploymentStep.COMMIT;
            default -> context.step();
        };
        return new DeploymentFailure(step, e.getMessage());
    }

    private Map<String, String> labels(DeploymentContext context, String commitSha) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("org.opencontainers.image.revision", commitSha);
        labels.put("org.opencontainers.image.source",
                properties.git().baseUrl().replaceAll("/+$", "") + "/" + context.deployment().repository());
        labels.put("dev.edgedeploy.deployment-id", context.deploymentId().toString());
        labels.put("dev.edgedeploy.project-id", context.projectId().toString());
        return labels;
    }

    private static String shortSha(String value) {
        return value != null && value.matches("^[0-9a-f]{40}$") ? value.substring(0, 7) : String.valueOf(value);
    }

    private static String shortImageId(String imageId) {
        String hex = imageId.startsWith("sha256:") ? imageId.substring(7) : imageId;
        return hex.length() > 12 ? hex.substring(0, 12) : hex;
    }
}
