package com.edgedeploy.worker.aws;

import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CPUArchitecture;
import software.amazon.awssdk.services.ecs.model.HealthCheck;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.LogDriver;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.OSFamily;
import software.amazon.awssdk.services.ecs.model.PortMapping;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.Secret;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.TransportProtocol;

import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the Fargate task definition for one deployment (a new revision of the project's family).
 * A pure function of its inputs, so it is unit-tested field by field.
 */
public final class EcsTaskDefinitionBuilder {

    /**
     * @param image          digest-pinned image reference
     * @param architecture   image CPU architecture from the build (amd64 / arm64); Fargate must match it
     * @param environment    plaintext container environment (non-secret values)
     * @param secrets        variable name -> SSM parameter ARN, resolved by ECS at task start
     * @param logStreamPrefix awslogs stream prefix; streams are {prefix}/{container}/{taskId}
     */
    public record Spec(String family, String containerName, String image, String architecture, int containerPort,
                       int cpu, int memory, String executionRoleArn, String taskRoleArn, Map<String, String> environment,
                       Map<String, String> secrets, String logGroup, String region, String logStreamPrefix,
                       boolean containerHealthCheck, String healthCheckPath, Map<String, String> tags) {

        @Override
        public String toString() {
            return "Spec[family=" + family + ", image=" + image + ", port=" + containerPort + ", cpu=" + cpu
                    + ", memory=" + memory + ", environment=" + environment.keySet() + ", secrets=" + secrets.keySet() + "]";
        }
    }

    private EcsTaskDefinitionBuilder() {
    }

    public static RegisterTaskDefinitionRequest build(Spec spec) {
        ContainerDefinition.Builder container = ContainerDefinition.builder()
                .name(spec.containerName())
                .image(spec.image())
                .essential(true)
                .portMappings(PortMapping.builder().containerPort(spec.containerPort()).protocol(TransportProtocol.TCP).build())
                .environment(new TreeMap<>(spec.environment()).entrySet().stream()
                        .map(e -> KeyValuePair.builder().name(e.getKey()).value(e.getValue()).build()).toList())
                .secrets(new TreeMap<>(spec.secrets()).entrySet().stream()
                        .map(e -> Secret.builder().name(e.getKey()).valueFrom(e.getValue()).build()).toList())
                .logConfiguration(LogConfiguration.builder()
                        .logDriver(LogDriver.AWSLOGS)
                        .options(Map.of(
                                "awslogs-group", spec.logGroup(),
                                "awslogs-region", spec.region(),
                                "awslogs-stream-prefix", spec.logStreamPrefix()))
                        .build())
                .dockerLabels(dockerLabels(spec.tags()));
        if (spec.containerHealthCheck()) {
            // busybox wget exists in both the node:*-alpine and nginx-unprivileged:*-alpine runtime images.
            container.healthCheck(HealthCheck.builder()
                    .command("CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1:" + spec.containerPort() + spec.healthCheckPath()
                            + " || exit 1")
                    .interval(15)
                    .timeout(5)
                    .retries(3)
                    .startPeriod(30)
                    .build());
        }

        RegisterTaskDefinitionRequest.Builder request = RegisterTaskDefinitionRequest.builder()
                .family(spec.family())
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(Compatibility.FARGATE)
                .cpu(String.valueOf(spec.cpu()))
                .memory(String.valueOf(spec.memory()))
                .executionRoleArn(spec.executionRoleArn())
                .runtimePlatform(p -> p.operatingSystemFamily(OSFamily.LINUX).cpuArchitecture(cpuArchitecture(spec.architecture())))
                .containerDefinitions(container.build())
                .tags(spec.tags().entrySet().stream().map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build()).toList());
        if (spec.taskRoleArn() != null && !spec.taskRoleArn().isBlank()) {
            request.taskRoleArn(spec.taskRoleArn());
        }
        return request.build();
    }

    /**
     * Docker label keys must match {@code ^[_\-a-zA-Z0-9./]+$}, so the AWS tag keys ({@code edgedeploy:project-id})
     * become reverse-DNS labels ({@code dev.edgedeploy.project-id}), the same convention as the image labels.
     */
    static Map<String, String> dockerLabels(Map<String, String> tags) {
        Map<String, String> labels = new TreeMap<>();
        tags.forEach((key, value) -> labels.put("dev." + key.replace(':', '.'), value));
        return labels;
    }

    /** Images built on Apple Silicon are arm64; Fargate supports both, but the task must say which. */
    static CPUArchitecture cpuArchitecture(String imageArchitecture) {
        return "arm64".equals(imageArchitecture) ? CPUArchitecture.ARM64 : CPUArchitecture.X86_64;
    }
}
