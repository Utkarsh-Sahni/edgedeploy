package com.edgedeploy.worker.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CPUArchitecture;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.LogDriver;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EcsTaskDefinitionBuilderTest {

    private static final String IMAGE = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/edgedeploy/p1@sha256:abc";

    @Test
    void buildsAFargateTaskDefinitionWithEverythingTheContainerNeeds() {
        RegisterTaskDefinitionRequest request = EcsTaskDefinitionBuilder.build(spec("arm64", true, "role-task"));

        assertThat(request.family()).isEqualTo("edgedeploy-p1");
        assertThat(request.networkMode()).isEqualTo(NetworkMode.AWSVPC);
        assertThat(request.requiresCompatibilities()).containsExactly(Compatibility.FARGATE);
        assertThat(request.cpu()).isEqualTo("256");
        assertThat(request.memory()).isEqualTo("512");
        assertThat(request.executionRoleArn()).isEqualTo("role-exec");
        assertThat(request.taskRoleArn()).isEqualTo("role-task");
        assertThat(request.runtimePlatform().cpuArchitecture()).isEqualTo(CPUArchitecture.ARM64);
        assertThat(request.tags()).anyMatch(t -> t.key().equals("edgedeploy:project-id") && t.value().equals("p1"));

        ContainerDefinition container = request.containerDefinitions().getFirst();
        assertThat(container.name()).isEqualTo("edgedeploy-app");
        assertThat(container.image()).isEqualTo(IMAGE); // digest-pinned
        assertThat(container.essential()).isTrue();
        // ECS rejects ':' in Docker label keys; AWS tags keep it.
        assertThat(container.dockerLabels()).containsExactly(Map.entry("dev.edgedeploy.project-id", "p1"));
        assertThat(container.dockerLabels().keySet()).allMatch(k -> k.matches("^[_\\-a-zA-Z0-9./]+$"));
        assertThat(container.portMappings()).singleElement().satisfies(p -> assertThat(p.containerPort()).isEqualTo(8080));
        assertThat(container.environment()).extracting(KeyValuePair::name).containsExactly("API_URL", "PORT"); // sorted
        assertThat(container.environment()).anyMatch(kv -> kv.name().equals("PORT") && kv.value().equals("8080"));
        assertThat(container.secrets()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("DATABASE_PASSWORD");
            assertThat(s.valueFrom()).startsWith("arn:aws:ssm:");
        });
        assertThat(container.logConfiguration().logDriver()).isEqualTo(LogDriver.AWSLOGS);
        assertThat(container.logConfiguration().options())
                .containsEntry("awslogs-group", "/edgedeploy/p1")
                .containsEntry("awslogs-region", "ap-south-1")
                .containsEntry("awslogs-stream-prefix", "d-1");
        assertThat(container.healthCheck().command()).containsExactly("CMD-SHELL",
                "wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1");
    }

    @Test
    void defaultsToX86AndOmitsOptionalParts() {
        RegisterTaskDefinitionRequest request = EcsTaskDefinitionBuilder.build(spec(null, false, ""));

        assertThat(request.runtimePlatform().cpuArchitecture()).isEqualTo(CPUArchitecture.X86_64);
        assertThat(request.taskRoleArn()).isNull();
        assertThat(request.containerDefinitions().getFirst().healthCheck()).isNull();
    }

    @Test
    void neverPrintsSecretValues() {
        assertThat(spec("amd64", true, null).toString()).doesNotContain("s3cr3t").doesNotContain("https://api.example.com");
    }

    private static EcsTaskDefinitionBuilder.Spec spec(String architecture, boolean healthCheck, String taskRole) {
        return new EcsTaskDefinitionBuilder.Spec("edgedeploy-p1", "edgedeploy-app", IMAGE, architecture, 8080, 256, 512,
                "role-exec", taskRole, Map.of("PORT", "8080", "API_URL", "https://api.example.com"),
                Map.of("DATABASE_PASSWORD", "arn:aws:ssm:ap-south-1:123456789012:parameter/edgedeploy/p1/env/DATABASE_PASSWORD"),
                "/edgedeploy/p1", "ap-south-1", "d-1", healthCheck, "/", Map.of("edgedeploy:project-id", "p1"));
    }
}
