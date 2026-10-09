package com.edgedeploy.worker.aws;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * {@code edgedeploy.aws.*}: where and how deployments run on AWS. Fields that only matter when
 * {@link #enabled()} is true are checked by {@link AwsConfigurationValidator} at startup.
 */
@Validated
@ConfigurationProperties("edgedeploy.aws")
public record AwsProperties(
        boolean enabled,
        String region,
        String accountId,
        @NotNull Routing routing,
        @Valid @NotNull Ecr ecr,
        @Valid @NotNull Ecs ecs,
        @Valid @NotNull Alb alb,
        @Valid @NotNull CloudWatch cloudwatch,
        @Valid @NotNull HealthCheck healthCheck,
        @Valid @NotNull Retry retry) {

    /**
     * @param repositoryPrefix repositories are {@code {prefix}/{projectId}}
     * @param keepImages       lifecycle policy: images kept per repository (older ones expire, saving storage)
     */
    public record Ecr(@NotBlank String repositoryPrefix, @Min(1) @Max(1000) int keepImages, @Min(1) @Max(10) int pushAttempts) {
    }

    /** How users reach a deployed application. */
    public enum Routing {
        /** One shared Application Load Balancer, a listener port per project: stable URLs, rolling updates. */
        ALB,
        /**
         * Budget mode: no load balancer. The single task's public IP is the URL ({@code http://ip:port}); it changes
         * with every deployment or task replacement. Saves the ALB's fixed hourly cost.
         */
        PUBLIC_IP
    }

    /** Where Fargate tasks run. */
    public enum Capacity {
        /** On-demand Fargate. */
        FARGATE,
        /**
         * Fargate Spot: spare capacity at a large discount that AWS can reclaim (ECS then starts a replacement).
         * Not available for ARM64 tasks: those fall back to on-demand Fargate.
         */
        FARGATE_SPOT
    }

    public enum SecretsMode {
        /** Environment variables are SSM SecureString parameters, referenced from the task definition. */
        SSM,
        /** Plaintext in the task definition. Development only. */
        ENVIRONMENT
    }

    public record Ecs(
            String cluster,
            String executionRoleArn,
            String taskRoleArn,
            List<String> subnetIds,
            String securityGroupId,
            boolean assignPublicIp,
            @Min(256) int cpu,
            @Min(512) int memory,
            @Min(1) @Max(10) int desiredCount,
            @NotBlank String containerName,
            boolean containerHealthCheck,
            @NotNull SecretsMode secretsMode,
            @NotNull Duration pollInterval,
            @NotNull Duration healthCheckGracePeriod,
            @Min(1) int maxFailedTasks,
            @NotNull Capacity capacity) {

        public List<String> subnets() {
            return subnetIds == null ? List.of() : subnetIds.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    /**
     * @param listenerPortStart first port handed out to projects (one HTTP listener per project)
     */
    public record Alb(String loadBalancerArn, String vpcId, @Min(1) @Max(65535) int listenerPortStart,
                      @Min(1) @Max(65535) int listenerPortEnd, @NotNull Duration deregistrationDelay) {
    }

    /** Container logs go to {@code {logGroupPrefix}/{projectId}}. */
    public record CloudWatch(@NotBlank String logGroupPrefix, @Min(1) int retentionDays) {
    }

    /** @param path URL path requested through the load balancer; HTTP 200-399 counts as healthy */
    public record HealthCheck(@NotBlank String path, @NotNull Duration requestTimeout) {
    }

    public record Retry(@Min(1) @Max(10) int maxAttempts) {
    }
}
