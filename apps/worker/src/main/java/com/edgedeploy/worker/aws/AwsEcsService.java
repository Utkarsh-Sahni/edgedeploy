package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.delivery.DeploymentTargetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.DeploymentConfiguration;
import software.amazon.awssdk.services.ecs.model.DeploymentRolloutState;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.LoadBalancer;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.PropagateTags;
import software.amazon.awssdk.services.ecs.model.ServiceField;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@link DeploymentTargetService} on ECS Fargate, behind an Application Load Balancer ({@code AWS_ROUTING_MODE=alb})
 * or, in budget mode ({@code public-ip}), reachable directly on the single task's public IP.
 *
 * <p><b>Service strategy: one long-lived ECS service per project</b> ({@code edgedeploy-{projectId}}). Each
 * deployment registers a new task definition revision (pinned to the image digest) and points the service at
 * it; ECS performs a rolling replacement (100% minimum healthy, 200% maximum) gated on ALB health checks, with
 * the deployment circuit breaker rolling back automatically if new tasks keep failing. Keeping every revision
 * makes later rollbacks a single UpdateService call.
 *
 * <p>A deployment is only reported healthy after: the ECS rollout COMPLETED with the desired number of tasks
 * running (gated by the container health check), the ALB considers the targets healthy (ALB mode), and an HTTP
 * request through the public URL succeeds.
 *
 * <p><b>Capacity:</b> {@code FARGATE} uses the FARGATE launch type. {@code FARGATE_SPOT} uses a capacity provider
 * strategy instead: FARGATE_SPOT for x86 images and, because Fargate Spot does not run ARM64 tasks, the FARGATE
 * provider for ARM64 images.
 */
@Service
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class AwsEcsService implements DeploymentTargetService {

    private static final Logger log = LoggerFactory.getLogger(AwsEcsService.class);

    private final EcsClient ecs;
    private final AlbRouting routing;
    private final CloudWatchLogGroups logGroups;
    private final SsmEnvironmentStore ssm;
    private final AwsResourceNames names;
    private final AwsProperties properties;
    private final HttpHealthProbe probe;
    private final TaskAddresses addresses;
    private final Sleeper sleeper;
    private final Clock clock;

    public AwsEcsService(EcsClient ecs, AlbRouting routing, CloudWatchLogGroups logGroups, SsmEnvironmentStore ssm,
                         AwsResourceNames names, AwsProperties properties, HttpHealthProbe probe, TaskAddresses addresses,
                         Sleeper sleeper, Clock clock) {
        this.ecs = ecs;
        this.routing = routing;
        this.logGroups = logGroups;
        this.ssm = ssm;
        this.names = names;
        this.properties = properties;
        this.probe = probe;
        this.addresses = addresses;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    // ---- start: task definition + routing + service ------------------------------------------------

    @Override
    public Rollout start(Release release, Consumer<String> progress) throws DeliveryException {
        UUID projectId = release.projectId();
        AwsProperties.Ecs config = properties.ecs();
        String cluster = config.cluster();
        String serviceName = names.ecsService(projectId);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(AwsTags.PROJECT, projectId.toString());
        tags.put(AwsTags.MANAGED, "true");

        String logGroup = names.logGroup(projectId);
        progress.accept("Preparing CloudWatch log group " + logGroup);
        logGroups.ensure(logGroup, tags);

        TaskDefinition taskDefinition = registerTaskDefinition(release, tags, logGroup, progress);

        boolean behindLoadBalancer = properties.routing() == AwsProperties.Routing.ALB;
        String targetGroupArn = null;
        String url = null;
        List<LoadBalancer> loadBalancers = List.of();
        if (behindLoadBalancer) {
            targetGroupArn = routing.ensureTargetGroup(projectId);
            int listenerPort = routing.ensureListener(targetGroupArn);
            url = routing.publicUrl(listenerPort);
            progress.accept("Load balancer routes " + url + " to the project's target group");
            loadBalancers = List.of(LoadBalancer.builder().targetGroupArn(targetGroupArn)
                    .containerName(config.containerName()).containerPort(release.containerPort()).build());
        } else {
            progress.accept("Budget mode: no load balancer, the application is served on the task's public IP "
                    + "(the address changes with every deployment)");
        }
        List<CapacityProviderStrategyItem> capacity = capacityStrategy(release, progress);

        Optional<software.amazon.awssdk.services.ecs.model.Service> existing = describeService(cluster, serviceName);
        List<LoadBalancer> serviceLoadBalancers = loadBalancers;
        try {
            if (existing.isPresent() && "ACTIVE".equals(existing.get().status())) {
                requireOwnedBy(existing.get(), projectId);
                requireCompatible(existing.get(), behindLoadBalancer, !capacity.isEmpty());
                progress.accept("Updating ECS service " + serviceName);
                ecs.updateService(r -> {
                    r.cluster(cluster)
                            .service(serviceName)
                            .taskDefinition(taskDefinition.taskDefinitionArn())
                            .desiredCount(config.desiredCount())
                            .networkConfiguration(networkConfiguration())
                            .deploymentConfiguration(deploymentConfiguration());
                    if (behindLoadBalancer) {
                        r.loadBalancers(serviceLoadBalancers)
                                .healthCheckGracePeriodSeconds((int) config.healthCheckGracePeriod().toSeconds());
                    }
                    if (!capacity.isEmpty()) {
                        // Required by ECS when the strategy changes (e.g. the image switched architecture).
                        r.capacityProviderStrategy(capacity).forceNewDeployment(true);
                    }
                });
            } else if (existing.isPresent() && "DRAINING".equals(existing.get().status())) {
                throw new DeliveryException("The project's previous ECS service is still being deleted; deploy again in a few minutes.");
            } else {
                progress.accept("Creating ECS service " + serviceName);
                ecs.createService(r -> {
                    r.cluster(cluster)
                            .serviceName(serviceName)
                            .taskDefinition(taskDefinition.taskDefinitionArn())
                            .desiredCount(config.desiredCount())
                            .networkConfiguration(networkConfiguration())
                            .deploymentConfiguration(deploymentConfiguration())
                            .propagateTags(PropagateTags.SERVICE)
                            .enableECSManagedTags(true)
                            .tags(ecsTags(tags))
                            // Same token on a retried request returns the same service instead of failing.
                            .clientToken(release.deploymentId().toString());
                    if (capacity.isEmpty()) {
                        r.launchType(LaunchType.FARGATE);
                    } else {
                        r.capacityProviderStrategy(capacity);
                    }
                    if (behindLoadBalancer) {
                        r.loadBalancers(serviceLoadBalancers)
                                .healthCheckGracePeriodSeconds((int) config.healthCheckGracePeriod().toSeconds());
                    }
                });
            }
        } catch (SdkException e) {
            throw AwsErrors.translate(existing.isPresent() ? "ecs:UpdateService" : "ecs:CreateService", e);
        }
        return new Rollout(cluster, serviceName, taskDefinition.taskDefinitionArn(), url, targetGroupArn,
                release.containerPort(), clock.instant());
    }

    /** Empty for on-demand Fargate (launch type); otherwise the capacity provider to run on. */
    private List<CapacityProviderStrategyItem> capacityStrategy(Release release, Consumer<String> progress) {
        if (properties.ecs().capacity() != AwsProperties.Capacity.FARGATE_SPOT) {
            return List.of();
        }
        boolean arm64 = "arm64".equalsIgnoreCase(release.imageArchitecture());
        if (arm64) {
            progress.accept("Fargate Spot does not run ARM64 images: using on-demand Fargate (ARM64 is still the cheaper "
                    + "on-demand option). Set DOCKER_BUILD_PLATFORM=linux/amd64 to build x86 images that can run on Spot.");
        } else {
            progress.accept("Running on Fargate Spot (discounted spare capacity; AWS may replace the task)");
        }
        return List.of(CapacityProviderStrategyItem.builder().capacityProvider(arm64 ? "FARGATE" : "FARGATE_SPOT").weight(1).build());
    }

    private TaskDefinition registerTaskDefinition(Release release, Map<String, String> tags, String logGroup,
                                                  Consumer<String> progress) throws DeliveryException {
        AwsProperties.Ecs config = properties.ecs();
        Map<String, String> environment = new TreeMap<>();
        Map<String, String> secrets = new TreeMap<>();
        if (!release.environment().isEmpty()) {
            if (config.secretsMode() == AwsProperties.SecretsMode.SSM) {
                // SSM rejects empty values; an empty value is not a secret anyway.
                Map<String, String> nonEmpty = release.environment().entrySet().stream().filter(e -> !e.getValue().isEmpty())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                release.environment().forEach((key, value) -> {
                    if (value.isEmpty()) {
                        environment.put(key, value);
                    }
                });
                secrets.putAll(ssm.sync(release.projectId(), nonEmpty));
                progress.accept("Injecting " + release.environment().size() + " environment variable(s) from SSM Parameter Store");
            } else {
                environment.putAll(release.environment());
                progress.accept("Injecting " + release.environment().size() + " environment variable(s)");
            }
        } else if (config.secretsMode() == AwsProperties.SecretsMode.SSM) {
            ssm.sync(release.projectId(), Map.of()); // remove parameters of variables that were deleted
        }
        // Set last so a user variable can never break the port wiring or the deployment metadata.
        environment.put("PORT", String.valueOf(release.containerPort()));
        environment.put("EDGEDEPLOY_DEPLOYMENT_ID", release.deploymentId().toString());
        if (release.commitSha() != null) {
            environment.put("EDGEDEPLOY_COMMIT_SHA", release.commitSha());
        }

        Map<String, String> labels = new LinkedHashMap<>(tags);
        labels.put("edgedeploy:deployment-id", release.deploymentId().toString());
        labels.put("edgedeploy:deployment-number", String.valueOf(release.deploymentNumber()));

        progress.accept("Registering ECS task definition (" + config.cpu() + " CPU units, " + config.memory()
                + " MiB, " + (release.imageArchitecture() != null ? release.imageArchitecture() : "amd64") + ")");
        try {
            TaskDefinition registered = ecs.registerTaskDefinition(EcsTaskDefinitionBuilder.build(new EcsTaskDefinitionBuilder.Spec(
                    names.taskDefinitionFamily(release.projectId()),
                    config.containerName(),
                    release.imageReference(),
                    release.imageArchitecture(),
                    release.containerPort(),
                    config.cpu(),
                    config.memory(),
                    config.executionRoleArn(),
                    config.taskRoleArn(),
                    environment,
                    secrets,
                    logGroup,
                    properties.region(),
                    release.deploymentId().toString(),
                    config.containerHealthCheck(),
                    properties.healthCheck().path(),
                    labels))).taskDefinition();
            progress.accept("Registered task definition " + registered.family() + ":" + registered.revision());
            return registered;
        } catch (SdkException e) {
            throw AwsErrors.translate("ecs:RegisterTaskDefinition", e);
        }
    }

    // ---- awaitStable: rollout progress, crashed tasks ------------------------------------------------

    @Override
    public Running awaitStable(Rollout rollout, Duration timeout, Consumer<String> progress) throws DeliveryException {
        Instant deadline = clock.instant().plus(timeout);
        Set<String> reportedStops = new HashSet<>();
        int failedTasks = 0;
        String lastSummary = null;
        progress.accept("Waiting for ECS deployment");
        while (true) {
            software.amazon.awssdk.services.ecs.model.Service service = describeService(rollout.cluster(), rollout.service())
                    .orElseThrow(() -> new DeliveryException("The ECS service disappeared during the deployment"));
            Optional<software.amazon.awssdk.services.ecs.model.Deployment> ours = service.deployments().stream()
                    .filter(d -> rollout.taskDefinitionArn().equals(d.taskDefinition()))
                    .findFirst();
            if (ours.isEmpty()) {
                throw new DeliveryException("ECS rolled back to the previous version: the new tasks did not become healthy");
            }

            for (Task task : stoppedTasks(rollout)) {
                if (reportedStops.add(task.taskArn())) {
                    failedTasks++;
                    String reason = stopReason(task);
                    progress.accept("Task stopped: " + reason);
                    if (failedTasks >= properties.ecs().maxFailedTasks()) {
                        throw new DeliveryException("Task stopped unexpectedly: " + reason);
                    }
                }
            }

            software.amazon.awssdk.services.ecs.model.Deployment deployment = ours.get();
            if (deployment.rolloutState() == DeploymentRolloutState.FAILED) {
                throw new DeliveryException("ECS deployment failed: " + AwsErrors.sanitize(deployment.rolloutStateReason()));
            }
            String summary = "ECS tasks running " + deployment.runningCount() + "/" + deployment.desiredCount()
                    + (deployment.pendingCount() > 0 ? ", pending " + deployment.pendingCount() : "");
            if (!summary.equals(lastSummary)) {
                progress.accept(summary);
                lastSummary = summary;
            }
            if (deployment.rolloutState() == DeploymentRolloutState.COMPLETED
                    && deployment.runningCount() >= deployment.desiredCount()) {
                Optional<Task> task = runningTask(rollout);
                if (rollout.targetGroupArn() != null) {
                    progress.accept("ECS deployment stabilized (" + deployment.runningCount() + "/" + deployment.desiredCount()
                            + " tasks running)");
                    return new Running(task.map(Task::taskArn).orElse(null), rollout.url());
                }
                // Budget mode: the running task's public IP is the address.
                Optional<String> ip = task.isPresent() ? addresses.publicIp(task.get()) : Optional.empty();
                if (ip.isPresent()) {
                    String url = "http://" + ip.get() + ":" + rollout.containerPort();
                    progress.accept("ECS deployment stabilized; the application is at " + url);
                    return new Running(task.get().taskArn(), url);
                }
                summary = "Waiting for the task's public IP address";
                if (!summary.equals(lastSummary)) {
                    progress.accept(summary);
                    lastSummary = summary;
                }
            }
            if (!clock.instant().isBefore(deadline)) {
                throw new DeliveryException("ECS service failed to stabilize within " + timeout.toSeconds() + "s ("
                        + summary + ")");
            }
            pause(properties.ecs().pollInterval(), deadline);
        }
    }

    private List<Task> stoppedTasks(Rollout rollout) throws DeliveryException {
        return tasks(rollout, DesiredStatus.STOPPED).stream()
                .filter(task -> rollout.taskDefinitionArn().equals(task.taskDefinitionArn()))
                .filter(task -> task.stoppedAt() == null || !task.stoppedAt().isBefore(rollout.startedAt()))
                .toList();
    }

    private Optional<Task> runningTask(Rollout rollout) throws DeliveryException {
        return tasks(rollout, DesiredStatus.RUNNING).stream()
                .filter(task -> rollout.taskDefinitionArn().equals(task.taskDefinitionArn()))
                .filter(task -> "RUNNING".equals(task.lastStatus()))
                .findFirst();
    }

    private List<Task> tasks(Rollout rollout, DesiredStatus status) throws DeliveryException {
        try {
            List<String> arns = ecs.listTasks(r -> r.cluster(rollout.cluster()).serviceName(rollout.service()).desiredStatus(status))
                    .taskArns();
            if (arns.isEmpty()) {
                return List.of();
            }
            return ecs.describeTasks(r -> r.cluster(rollout.cluster()).tasks(arns.subList(0, Math.min(100, arns.size())))).tasks();
        } catch (SdkException e) {
            throw AwsErrors.translate("ecs:DescribeTasks", e);
        }
    }

    /** "Essential container in task exited (exit code 1)", "CannotPullContainerError: ...", etc., sanitized. */
    static String stopReason(Task task) {
        StringBuilder reason = new StringBuilder(task.stoppedReason() != null ? task.stoppedReason() : "stopped");
        for (Container container : task.containers()) {
            if (container.exitCode() != null) {
                reason.append(" (exit code ").append(container.exitCode()).append(')');
            }
            if (container.reason() != null && !container.reason().isBlank()) {
                reason.append(": ").append(container.reason());
            }
        }
        return AwsErrors.sanitize(reason.toString());
    }

    // ---- verifyHealth: load balancer, then the public URL ------------------------------------------

    @Override
    public void verifyHealth(Rollout rollout, Duration timeout, Consumer<String> progress) throws DeliveryException {
        Instant deadline = clock.instant().plus(timeout);
        int desired = properties.ecs().desiredCount();

        if (rollout.targetGroupArn() != null) {
            progress.accept("Checking load balancer target health");
        }
        while (rollout.targetGroupArn() != null) {
            AlbRouting.TargetHealthSummary health = routing.targetHealth(rollout.targetGroupArn());
            if (health.healthy() >= desired) {
                progress.accept("Load balancer targets healthy (" + health.healthy() + "/" + health.total() + ")");
                break;
            }
            if (!clock.instant().isBefore(deadline)) {
                throw new DeliveryException("Health check failed: load balancer targets are not healthy"
                        + (health.problem() != null ? " (" + health.problem() + ")" : ""));
            }
            pause(Duration.ofSeconds(5), deadline);
        }

        URI uri = URI.create(rollout.url() + properties.healthCheck().path());
        progress.accept("Running application health check: GET " + uri);
        String last = "no response";
        while (true) {
            try {
                int status = probe.status(uri, properties.healthCheck().requestTimeout());
                if (status >= 200 && status < 400) {
                    progress.accept("Health check passed: HTTP " + status);
                    return;
                }
                last = "HTTP " + status;
            } catch (IOException e) {
                last = "no response (" + e.getClass().getSimpleName() + ")";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeliveryException("Health check interrupted", e);
            }
            if (!clock.instant().isBefore(deadline)) {
                throw new DeliveryException("Health check failed: " + uri + " answered " + last);
            }
            pause(Duration.ofSeconds(5), deadline);
        }
    }

    // ---- restore -------------------------------------------------------------------------------

    @Override
    public void restore(Rollout rollout, String previousVersion, Consumer<String> progress) {
        try {
            if (previousVersion != null) {
                ecs.updateService(r -> r.cluster(rollout.cluster()).service(rollout.service()).taskDefinition(previousVersion));
                progress.accept("Rolled the ECS service back to the previous running version");
            } else {
                ecs.updateService(r -> r.cluster(rollout.cluster()).service(rollout.service()).desiredCount(0));
                progress.accept("Scaled the ECS service to 0 tasks: there is no earlier working version to fall back to");
            }
        } catch (SdkException e) {
            log.warn("Could not restore ECS service {}: {}", rollout.service(), e.toString());
            progress.accept("Could not restore the ECS service: " + AwsErrors.translate("ecs:UpdateService", e).getMessage());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Optional<software.amazon.awssdk.services.ecs.model.Service> describeService(String cluster, String serviceName)
            throws DeliveryException {
        try {
            return ecs.describeServices(r -> r.cluster(cluster).services(serviceName).include(ServiceField.TAGS))
                    .services().stream().findFirst();
        } catch (SdkException e) {
            throw AwsErrors.translate("ecs:DescribeServices", e);
        }
    }

    /** Names derive from the project id, but never update a service another project (or a person) owns. */
    private static void requireOwnedBy(software.amazon.awssdk.services.ecs.model.Service service, UUID projectId)
            throws DeliveryException {
        boolean owned = service.tags().stream()
                .anyMatch(tag -> AwsTags.PROJECT.equals(tag.key()) && projectId.toString().equals(tag.value()));
        if (!owned) {
            throw new DeliveryException("ECS service " + service.serviceName() + " exists but is not managed by EdgeDeploy for "
                    + "this project; refusing to modify it");
        }
    }

    /**
     * ECS cannot switch an existing service between "behind a load balancer" and "no load balancer", or from the
     * launch type to a capacity provider strategy, in a way that is safe to do implicitly. Ask for a clean slate.
     */
    private static void requireCompatible(software.amazon.awssdk.services.ecs.model.Service service, boolean behindLoadBalancer,
                                          boolean usesCapacityProviders) throws DeliveryException {
        if (service.loadBalancers().isEmpty() == behindLoadBalancer) {
            throw new DeliveryException("ECS service " + service.serviceName() + " was created for the other routing mode "
                    + "(AWS_ROUTING_MODE changed). Delete it first (scripts/aws-cleanup.sh), then deploy again.");
        }
        if (usesCapacityProviders && service.launchType() != null) {
            throw new DeliveryException("ECS service " + service.serviceName() + " was created with on-demand Fargate "
                    + "(AWS_ECS_CAPACITY changed). Delete it first (scripts/aws-cleanup.sh), then deploy again.");
        }
    }

    private NetworkConfiguration networkConfiguration() {
        AwsProperties.Ecs config = properties.ecs();
        return NetworkConfiguration.builder().awsvpcConfiguration(v -> v
                .subnets(config.subnets())
                .securityGroups(config.securityGroupId())
                .assignPublicIp(config.assignPublicIp() ? AssignPublicIp.ENABLED : AssignPublicIp.DISABLED)).build();
    }

    private static DeploymentConfiguration deploymentConfiguration() {
        return DeploymentConfiguration.builder()
                .minimumHealthyPercent(100)
                .maximumPercent(200)
                .deploymentCircuitBreaker(c -> c.enable(true).rollback(true))
                .build();
    }

    private static List<Tag> ecsTags(Map<String, String> tags) {
        return tags.entrySet().stream().map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build()).toList();
    }

    private void pause(Duration interval, Instant deadline) throws DeliveryException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        try {
            sleeper.sleep(remaining.compareTo(interval) < 0 ? (remaining.isNegative() ? Duration.ZERO : remaining) : interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException("Deployment interrupted", e);
        }
    }
}
