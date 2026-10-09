package com.edgedeploy.worker.aws;

import java.time.Duration;
import java.util.List;

/** A complete, valid AWS configuration for unit tests (no real account behind it). */
final class AwsTestProperties {

    static final String ACCOUNT = "123456789012";
    static final String REGION = "ap-south-1";
    static final String ALB_ARN = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:loadbalancer/app/edgedeploy/0123456789abcdef";
    static final String EXECUTION_ROLE = "arn:aws:iam::123456789012:role/edgedeploy-task-execution";
    static final String TASK_ROLE = "arn:aws:iam::123456789012:role/edgedeploy-app-task";

    private AwsTestProperties() {
    }

    static AwsProperties create() {
        return create(AwsProperties.SecretsMode.SSM);
    }

    static AwsProperties create(AwsProperties.SecretsMode secretsMode) {
        return create(secretsMode, AwsProperties.Routing.ALB, AwsProperties.Capacity.FARGATE);
    }

    /** Budget mode: no load balancer, Fargate Spot. */
    static AwsProperties budget() {
        return create(AwsProperties.SecretsMode.SSM, AwsProperties.Routing.PUBLIC_IP, AwsProperties.Capacity.FARGATE_SPOT);
    }

    static AwsProperties create(AwsProperties.SecretsMode secretsMode, AwsProperties.Routing routing,
                                AwsProperties.Capacity capacity) {
        return new AwsProperties(
                true,
                REGION,
                ACCOUNT,
                routing,
                new AwsProperties.Ecr("edgedeploy", 10, 3),
                new AwsProperties.Ecs("edgedeploy", EXECUTION_ROLE, TASK_ROLE,
                        List.of("subnet-0a1b2c3d4e5f60718", "subnet-0f1e2d3c4b5a69788"), "sg-0123456789abcdef0", true,
                        256, 512, 1, "edgedeploy-app", true, secretsMode, Duration.ofSeconds(10), Duration.ofSeconds(60), 2,
                        capacity),
                new AwsProperties.Alb(ALB_ARN, "vpc-0123456789abcdef0", 10000, 10049, Duration.ofSeconds(30)),
                new AwsProperties.CloudWatch("/edgedeploy", 7),
                new AwsProperties.HealthCheck("/", Duration.ofSeconds(5)),
                new AwsProperties.Retry(3));
    }
}
