package com.edgedeploy.worker.aws;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.deployment.DeploymentStore;
import com.edgedeploy.worker.kafka.DeploymentEventPublisher;
import com.edgedeploy.worker.logging.DeploymentLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;
import software.amazon.awssdk.services.ecs.model.Task;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Budget mode only. The URL of a deployment is its task's public IP, but ECS can replace that task after the
 * deployment finished (a Fargate Spot interruption, a crash, host maintenance), and the replacement gets a new IP.
 * Once a minute this notices replacements and moves the deployment's URL to the task now serving it, so
 * "Open Application" keeps working.
 */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class PublicIpAddressRefresher {

    private static final Logger log = LoggerFactory.getLogger(PublicIpAddressRefresher.class);
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final DeploymentStore store;
    private final EcsClient ecs;
    private final TaskAddresses addresses;
    private final DeploymentLogService logs;
    private final DeploymentEventPublisher events;
    private final AwsProperties properties;

    public PublicIpAddressRefresher(DeploymentStore store, EcsClient ecs, TaskAddresses addresses, DeploymentLogService logs,
                                    DeploymentEventPublisher events, AwsProperties properties) {
        this.store = store;
        this.ecs = ecs;
        this.addresses = addresses;
        this.logs = logs;
        this.events = events;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void refreshAll() {
        if (properties.routing() != AwsProperties.Routing.PUBLIC_IP) {
            return;
        }
        for (DeploymentStore.LiveDeployment deployment : store.runningOnEcs()) {
            try {
                refresh(deployment);
            } catch (DeliveryException | RuntimeException e) {
                log.warn("Could not refresh the address of deployment {}: {}", deployment.deploymentId(), e.getMessage());
            }
        }
    }

    /** @return true when the deployment moved to a new address */
    boolean refresh(DeploymentStore.LiveDeployment deployment) throws DeliveryException {
        URI current = deployment.url() == null ? null : URI.create(deployment.url());
        if (current == null || current.getHost() == null || current.getPort() < 0 || !IPV4.matcher(current.getHost()).matches()) {
            return false; // not a budget-mode address (e.g. served by the load balancer)
        }
        List<Task> running = runningTasks(deployment).stream()
                .filter(task -> deployment.taskDefinitionArn().equals(task.taskDefinitionArn()))
                .filter(task -> "RUNNING".equals(task.lastStatus()))
                .toList();
        if (running.isEmpty() || running.stream().anyMatch(task -> task.taskArn().equals(deployment.taskArn()))) {
            return false; // still served by the same task, or the replacement is not up yet
        }
        Task replacement = running.getFirst();
        Optional<String> ip = addresses.publicIp(replacement);
        if (ip.isEmpty()) {
            return false;
        }
        String url = "http://" + ip.get() + ":" + current.getPort();
        if (!store.updateLiveAddress(deployment.deploymentId(), deployment.taskArn(), replacement.taskArn(), url)) {
            return false; // a newer deployment took over, or another worker already updated it
        }
        String message = "ECS replaced the application's task (for example a Fargate Spot interruption); "
                + "it is now served at " + url;
        log.info("Deployment {} moved to {}", deployment.deploymentId(), url);
        logs.append(deployment.deploymentId(), List.of(new DeploymentLogService.Entry(LogLevel.INFO, null, message)));
        events.statusChanged(deployment.deploymentId(), deployment.projectId(), DeploymentStatus.RUNNING, url, message);
        return true;
    }

    private List<Task> runningTasks(DeploymentStore.LiveDeployment deployment) throws DeliveryException {
        try {
            List<String> arns = ecs.listTasks(r -> r.cluster(deployment.cluster()).serviceName(deployment.service())
                    .desiredStatus(DesiredStatus.RUNNING)).taskArns();
            if (arns.isEmpty()) {
                return List.of();
            }
            return ecs.describeTasks(r -> r.cluster(deployment.cluster()).tasks(arns.subList(0, Math.min(100, arns.size()))))
                    .tasks();
        } catch (SdkException e) {
            throw AwsErrors.translate("ecs:DescribeTasks", e);
        }
    }
}
