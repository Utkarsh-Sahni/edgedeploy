package com.edgedeploy.worker.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the AWS settings needed to deploy, so a misconfiguration fails at startup with every problem
 * listed, instead of halfway through someone's deployment.
 */
public final class AwsConfigurationValidator {

    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-gov)?-[a-z]+-\\d$");
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{12}$");
    private static final Pattern ROLE_ARN = Pattern.compile("^arn:aws[a-z-]*:iam::\\d{12}:role/[\\w+=,.@/-]{1,512}$");
    private static final Pattern CLUSTER = Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
    private static final Pattern SUBNET = Pattern.compile("^subnet-[0-9a-f]{8,17}$");
    private static final Pattern SECURITY_GROUP = Pattern.compile("^sg-[0-9a-f]{8,17}$");
    private static final Pattern VPC = Pattern.compile("^vpc-[0-9a-f]{8,17}$");
    private static final Pattern ALB_ARN = Pattern.compile("^arn:aws[a-z-]*:elasticloadbalancing:[a-z0-9-]+:\\d{12}:loadbalancer/app/[A-Za-z0-9-]{1,32}/[0-9a-f]+$");
    private static final Pattern LOG_GROUP_PREFIX = Pattern.compile("^/[A-Za-z0-9_\\-/.#]{1,200}$");
    private static final Pattern HEALTH_PATH = Pattern.compile("^/[A-Za-z0-9._~/-]{0,200}$");
    private static final Set<Integer> RETENTION_DAYS = Set.of(1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545,
            731, 1096, 1827, 2192, 2557, 2922, 3288, 3653);
    /** Fargate CPU units -> allowed memory (MiB) range and step. */
    private static final Map<Integer, int[]> FARGATE_MEMORY = Map.of(
            256, new int[]{512, 2048, 512},
            512, new int[]{1024, 4096, 1024},
            1024, new int[]{2048, 8192, 1024},
            2048, new int[]{4096, 16384, 1024},
            4096, new int[]{8192, 30720, 1024});

    private AwsConfigurationValidator() {
    }

    /** @return human-readable problems; empty when the configuration can deploy */
    public static List<String> problems(AwsProperties aws) {
        List<String> problems = new ArrayList<>();
        require(problems, aws.region(), REGION, "AWS_REGION", "e.g. ap-south-1");
        require(problems, aws.accountId(), ACCOUNT, "AWS_ACCOUNT_ID", "12 digits");

        AwsProperties.Ecs ecs = aws.ecs();
        require(problems, ecs.cluster(), CLUSTER, "AWS_ECS_CLUSTER", "cluster name");
        require(problems, ecs.executionRoleArn(), ROLE_ARN, "AWS_ECS_EXECUTION_ROLE_ARN", "IAM role ARN");
        if (ecs.taskRoleArn() != null && !ecs.taskRoleArn().isBlank() && !ROLE_ARN.matcher(ecs.taskRoleArn()).matches()) {
            problems.add("AWS_ECS_TASK_ROLE_ARN must be an IAM role ARN (or empty)");
        }
        if (ecs.subnets().isEmpty()) {
            problems.add("AWS_ECS_SUBNET_IDS must list at least one subnet (comma-separated)");
        }
        ecs.subnets().stream().filter(s -> !SUBNET.matcher(s).matches())
                .forEach(s -> problems.add("AWS_ECS_SUBNET_IDS contains an invalid subnet id: " + s));
        require(problems, ecs.securityGroupId(), SECURITY_GROUP, "AWS_ECS_SECURITY_GROUP_ID", "sg-...");
        int[] memory = FARGATE_MEMORY.get(ecs.cpu());
        if (memory == null) {
            problems.add("AWS_ECS_CPU must be one of 256, 512, 1024, 2048, 4096");
        } else if (ecs.memory() < memory[0] || ecs.memory() > memory[1] || (ecs.memory() - memory[0]) % memory[2] != 0) {
            problems.add("AWS_ECS_MEMORY " + ecs.memory() + " is not valid for " + ecs.cpu() + " CPU units on Fargate ("
                    + memory[0] + "-" + memory[1] + " in steps of " + memory[2] + ")");
        }

        if (aws.routing() == AwsProperties.Routing.PUBLIC_IP) {
            // The task's own public IP is the URL: it needs one, and there is exactly one task to point at.
            if (!ecs.assignPublicIp()) {
                problems.add("AWS_ROUTING_MODE=public-ip needs AWS_ECS_ASSIGN_PUBLIC_IP=true (the URL is the task's public IP)");
            }
            if (ecs.desiredCount() != 1) {
                problems.add("AWS_ROUTING_MODE=public-ip serves a single task: set AWS_ECS_DESIRED_COUNT=1");
            }
        } else {
            AwsProperties.Alb alb = aws.alb();
            require(problems, alb.loadBalancerArn(), ALB_ARN, "AWS_ALB_ARN", "Application Load Balancer ARN");
            require(problems, alb.vpcId(), VPC, "AWS_VPC_ID", "vpc-...");
            if (alb.listenerPortStart() < 1024 || alb.listenerPortEnd() < alb.listenerPortStart()) {
                problems.add("AWS_ALB_LISTENER_PORT_START/END must be a range of ports >= 1024");
            } else if (alb.listenerPortEnd() - alb.listenerPortStart() + 1 > 50) {
                problems.add("The ALB listener port range may span at most 50 ports (an ALB supports 50 listeners)");
            }
        }

        if (!LOG_GROUP_PREFIX.matcher(aws.cloudwatch().logGroupPrefix()).matches()) {
            problems.add("AWS_LOG_GROUP must be a log group prefix such as /edgedeploy");
        }
        if (!RETENTION_DAYS.contains(aws.cloudwatch().retentionDays())) {
            problems.add("AWS_LOG_RETENTION_DAYS must be a CloudWatch retention value (1, 3, 5, 7, 14, 30, ...)");
        }
        if (!HEALTH_PATH.matcher(aws.healthCheck().path()).matches()) {
            problems.add("APP_HEALTH_CHECK_PATH must be a plain URL path such as / or /health");
        }
        return problems;
    }

    private static void require(List<String> problems, String value, Pattern pattern, String name, String hint) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required (" + hint + ")");
        } else if (!pattern.matcher(value.trim()).matches()) {
            problems.add(name + " is not valid (" + hint + ")");
        }
    }
}
