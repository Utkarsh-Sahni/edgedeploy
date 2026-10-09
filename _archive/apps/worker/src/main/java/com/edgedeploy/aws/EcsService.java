package com.edgedeploy.aws;

import com.edgedeploy.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.LogDriver;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.ServiceNotFoundException;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class EcsService {

    private final EcsClient ecsClient;
    private final WorkerProperties properties;

    public EcsService(WorkerProperties properties) {
        this.properties = properties;
        this.ecsClient = EcsClient.builder()
                .region(Region.of(properties.getAws().getRegion()))
                .build();
    }

    public String deploy(UUID projectId, UUID deploymentId, String imageUri, Map<String, String> env) {
        String family = "edgedeploy-" + projectId;
        List<KeyValuePair> environment = env.entrySet().stream()
                .map(entry -> KeyValuePair.builder().name(entry.getKey()).value(entry.getValue()).build())
                .toList();

        var registered = ecsClient.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(Compatibility.FARGATE)
                .cpu(String.valueOf(properties.getAws().getCpu()))
                .memory(String.valueOf(properties.getAws().getMemory()))
                .executionRoleArn(properties.getAws().getExecutionRoleArn())
                .taskRoleArn(properties.getAws().getTaskRoleArn())
                .containerDefinitions(ContainerDefinition.builder()
                        .name("app")
                        .image(imageUri)
                        .essential(true)
                        .portMappings(PortMapping.builder()
                                .containerPort(properties.getAws().getContainerPort())
                                .build())
                        .environment(environment)
                        .logConfiguration(LogConfiguration.builder()
                                .logDriver(LogDriver.AWSLOGS)
                                .options(Map.of(
                                        "awslogs-group", "/edgedeploy/" + projectId,
                                        "awslogs-region", properties.getAws().getRegion(),
                                        "awslogs-stream-prefix", "app"
                                ))
                                .build())
                        .build())
                .build());

        String taskDefinition = registered.taskDefinition().taskDefinitionArn();
        String serviceName = family;
        NetworkConfiguration network = NetworkConfiguration.builder()
                .awsvpcConfiguration(AwsVpcConfiguration.builder()
                        .subnets(split(properties.getAws().getSubnets()))
                        .securityGroups(split(properties.getAws().getSecurityGroups()))
                        .assignPublicIp(AssignPublicIp.ENABLED)
                        .build())
                .build();

        if (serviceExists(serviceName)) {
            ecsClient.updateService(UpdateServiceRequest.builder()
                    .cluster(properties.getAws().getCluster())
                    .service(serviceName)
                    .taskDefinition(taskDefinition)
                    .forceNewDeployment(true)
                    .build());
        } else {
            ecsClient.createService(CreateServiceRequest.builder()
                    .cluster(properties.getAws().getCluster())
                    .serviceName(serviceName)
                    .taskDefinition(taskDefinition)
                    .desiredCount(1)
                    .launchType(LaunchType.FARGATE)
                    .networkConfiguration(network)
                    .build());
        }
        waitForStable(serviceName);
        String host = deploymentId.toString().substring(0, 8) + "." + properties.getDeployment().getBaseDomain();
        return properties.getDeployment().getScheme() + "://" + host;
    }

    private boolean serviceExists(String serviceName) {
        try {
            var response = ecsClient.describeServices(DescribeServicesRequest.builder()
                    .cluster(properties.getAws().getCluster())
                    .services(serviceName)
                    .build());
            return response.services().stream().anyMatch(service -> service.status() != null && !"INACTIVE".equals(service.status()));
        } catch (ServiceNotFoundException ex) {
            return false;
        }
    }

    private void waitForStable(String serviceName) {
        for (int i = 0; i < 30; i++) {
            List<Service> services = ecsClient.describeServices(DescribeServicesRequest.builder()
                    .cluster(properties.getAws().getCluster())
                    .services(serviceName)
                    .build()).services();
            if (!services.isEmpty() && services.getFirst().runningCount() >= 1 && services.getFirst().deployments().size() <= 1) {
                return;
            }
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for ECS service", ex);
            }
        }
        throw new IllegalStateException("ECS service did not stabilize in time");
    }

    private List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}
