package com.edgedeploy.worker.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Resource naming, startup validation and user-facing error translation. */
class AwsConfigurationTest {

    private static final UUID PROJECT = UUID.fromString("8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");

    @Test
    void resourceNamesAreDeterministicAndValid() {
        AwsResourceNames names = new AwsResourceNames(AwsTestProperties.create());

        assertThat(names.ecrRepository(PROJECT)).isEqualTo("edgedeploy/8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");
        assertThat(names.ecsService(PROJECT)).isEqualTo("edgedeploy-8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");
        assertThat(names.taskDefinitionFamily(PROJECT)).isEqualTo("edgedeploy-8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");
        assertThat(names.logGroup(PROJECT)).isEqualTo("/edgedeploy/8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");
        assertThat(names.parameterName(PROJECT, "API_URL")).isEqualTo("/edgedeploy/8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6/env/API_URL");
        // ALB target group names are limited to 32 characters.
        assertThat(names.targetGroup(PROJECT)).isEqualTo("ed-8f42d1a05b6c4d7e8f90a1b2c3d4e").hasSize(32);
        assertThat(names.targetGroup(PROJECT)).isEqualTo(names.targetGroup(UUID.fromString(PROJECT.toString())));
    }

    @Test
    void validConfigurationHasNoProblems() {
        assertThat(AwsConfigurationValidator.problems(AwsTestProperties.create())).isEmpty();
    }

    @Test
    void reportsEveryMissingOrInvalidSettingAtOnce() {
        AwsProperties valid = AwsTestProperties.create();
        AwsProperties broken = new AwsProperties(true, "", "12345", AwsProperties.Routing.ALB, valid.ecr(),
                new AwsProperties.Ecs("", "not-an-arn", "", List.of(" "), "sg-zzz", true, 256, 4096, 1, "edgedeploy-app",
                        true, AwsProperties.SecretsMode.SSM, Duration.ofSeconds(10), Duration.ofSeconds(60), 2,
                        AwsProperties.Capacity.FARGATE),
                new AwsProperties.Alb("", "vpc-1", 9000, 9100, Duration.ofSeconds(30)),
                new AwsProperties.CloudWatch("/edgedeploy", 10),
                new AwsProperties.HealthCheck("/health; rm -rf /", Duration.ofSeconds(5)),
                valid.retry());

        List<String> problems = AwsConfigurationValidator.problems(broken);

        assertThat(problems).anyMatch(p -> p.startsWith("AWS_REGION"))
                .anyMatch(p -> p.startsWith("AWS_ACCOUNT_ID"))
                .anyMatch(p -> p.startsWith("AWS_ECS_CLUSTER"))
                .anyMatch(p -> p.startsWith("AWS_ECS_EXECUTION_ROLE_ARN"))
                .anyMatch(p -> p.startsWith("AWS_ECS_SUBNET_IDS"))
                .anyMatch(p -> p.startsWith("AWS_ECS_SECURITY_GROUP_ID"))
                .anyMatch(p -> p.contains("AWS_ECS_MEMORY 4096 is not valid for 256 CPU"))
                .anyMatch(p -> p.startsWith("AWS_ALB_ARN"))
                .anyMatch(p -> p.startsWith("AWS_VPC_ID"))
                .anyMatch(p -> p.contains("at most 50 ports"))
                .anyMatch(p -> p.startsWith("AWS_LOG_RETENTION_DAYS"))
                .anyMatch(p -> p.startsWith("APP_HEALTH_CHECK_PATH"));
    }

    @Test
    void errorsAreActionableWithoutLeakingInfrastructureDetails() {
        AwsServiceException denied = AwsServiceException.builder().awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("AccessDeniedException")
                .errorMessage("User: arn:aws:iam::123456789012:user/x is not authorized to perform: ecs:UpdateService")
                .build()).statusCode(400).build();
        AwsServiceException invalid = AwsServiceException.builder().awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("InvalidParameterException")
                .errorMessage("Role arn:aws:iam::123456789012:role/missing cannot be assumed by account 123456789012")
                .build()).statusCode(400).build();
        AwsServiceException throttled = AwsServiceException.builder().awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("ThrottlingException").build()).statusCode(400).build();

        assertThat(AwsErrors.translate("ecs:UpdateService", denied).getMessage())
                .isEqualTo("The EdgeDeploy worker is not authorized to perform ecs:UpdateService. Check the worker's IAM policy (docs/aws-setup.md).");
        assertThat(AwsErrors.translate("ecs:CreateService", invalid).getMessage())
                .contains("InvalidParameterException").doesNotContain("123456789012").doesNotContain("arn:aws");
        assertThat(AwsErrors.translate("ecr:PutImage", throttled).getMessage()).contains("rate limit");
        assertThat(AwsErrors.translate("ecs:DescribeServices", SdkClientException.create("Unable to execute HTTP request")).getMessage())
                .contains("network error");
    }

    @Test
    void budgetModeNeedsNoLoadBalancerButOnePublicTask() {
        AwsProperties budget = AwsTestProperties.budget();
        AwsProperties withoutAlb = new AwsProperties(true, budget.region(), budget.accountId(), budget.routing(), budget.ecr(),
                budget.ecs(), new AwsProperties.Alb("", "", 10000, 10049, Duration.ofSeconds(30)), budget.cloudwatch(),
                budget.healthCheck(), budget.retry());
        assertThat(AwsConfigurationValidator.problems(withoutAlb)).isEmpty();

        AwsProperties.Ecs ecs = budget.ecs();
        AwsProperties scaledOut = new AwsProperties(true, budget.region(), budget.accountId(), budget.routing(), budget.ecr(),
                new AwsProperties.Ecs(ecs.cluster(), ecs.executionRoleArn(), ecs.taskRoleArn(), ecs.subnetIds(),
                        ecs.securityGroupId(), false, ecs.cpu(), ecs.memory(), 2, ecs.containerName(), ecs.containerHealthCheck(),
                        ecs.secretsMode(), ecs.pollInterval(), ecs.healthCheckGracePeriod(), ecs.maxFailedTasks(), ecs.capacity()),
                withoutAlb.alb(), budget.cloudwatch(), budget.healthCheck(), budget.retry());
        assertThat(AwsConfigurationValidator.problems(scaledOut)).containsExactlyInAnyOrder(
                "AWS_ROUTING_MODE=public-ip needs AWS_ECS_ASSIGN_PUBLIC_IP=true (the URL is the task's public IP)",
                "AWS_ROUTING_MODE=public-ip serves a single task: set AWS_ECS_DESIRED_COUNT=1");
    }
}
