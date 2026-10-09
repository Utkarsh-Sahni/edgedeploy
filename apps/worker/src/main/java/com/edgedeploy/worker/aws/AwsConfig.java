package com.edgedeploy.worker.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Duration;
import java.util.List;

/**
 * AWS SDK v2 clients, created only when {@code edgedeploy.aws.enabled=true}.
 *
 * <ul>
 *   <li><b>Credentials</b>: the standard provider chain (environment, shared config/SSO profile, container
 *       or instance role). Nothing is configured in EdgeDeploy itself.</li>
 *   <li><b>Retries</b>: the SDK's standard strategy, bounded by {@code edgedeploy.aws.retry.max-attempts}:
 *       exponential backoff with jitter, applied only to retryable errors (throttling, 5xx, network).
 *       Access denied, validation and invalid-parameter errors fail immediately.</li>
 *   <li><b>Timeouts</b> per attempt and per call, so a hung connection cannot stall a deployment.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class AwsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsConfig.class);

    @Bean
    AwsResourceNames awsResourceNames(AwsProperties properties) {
        return new AwsResourceNames(properties);
    }

    @Bean(destroyMethod = "close")
    DefaultCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    ClientOverrideConfiguration awsClientConfiguration(AwsProperties properties) {
        return clientConfiguration(properties.retry().maxAttempts());
    }

    /** Shared by the beans and the retry tests. */
    static ClientOverrideConfiguration clientConfiguration(int maxAttempts) {
        return ClientOverrideConfiguration.builder()
                .retryStrategy(AwsRetryStrategy.standardRetryStrategy().toBuilder().maxAttempts(maxAttempts).build())
                .apiCallAttemptTimeout(Duration.ofSeconds(30))
                .apiCallTimeout(Duration.ofMinutes(2))
                .build();
    }

    @Bean(destroyMethod = "close")
    EcrClient ecrClient(AwsProperties p, AwsCredentialsProvider credentials, ClientOverrideConfiguration config) {
        return EcrClient.builder().region(Region.of(p.region())).credentialsProvider(credentials).overrideConfiguration(config).build();
    }

    @Bean(destroyMethod = "close")
    EcsClient ecsClient(AwsProperties p, AwsCredentialsProvider credentials, ClientOverrideConfiguration config) {
        return EcsClient.builder().region(Region.of(p.region())).credentialsProvider(credentials).overrideConfiguration(config).build();
    }

    @Bean(destroyMethod = "close")
    ElasticLoadBalancingV2Client elbClient(AwsProperties p, AwsCredentialsProvider credentials, ClientOverrideConfiguration config) {
        return ElasticLoadBalancingV2Client.builder().region(Region.of(p.region())).credentialsProvider(credentials)
                .overrideConfiguration(config).build();
    }

    @Bean(destroyMethod = "close")
    CloudWatchLogsClient logsClient(AwsProperties p, AwsCredentialsProvider credentials, ClientOverrideConfiguration config) {
        return CloudWatchLogsClient.builder().region(Region.of(p.region())).credentialsProvider(credentials)
                .overrideConfiguration(config).build();
    }

    @Bean(destroyMethod = "close")
    Ec2Client ec2Client(AwsProperties p, AwsCredentialsProvider credentials, ClientOverrideConfiguration config) {
        return Ec2Client.builder().region(Region.of(p.region())).credentialsProvider(credentials).overrideConfiguration(config).build();
    }

    @Bean(destroyMethod = "close")
    SsmClient ssmClient(AwsProperties p, AwsCredentialsProvider credentials, ClientOverrideConfiguration config) {
        return SsmClient.builder().region(Region.of(p.region())).credentialsProvider(credentials).overrideConfiguration(config).build();
    }

    /**
     * Fails startup on invalid configuration, missing credentials, or credentials for a different account
     * than AWS_ACCOUNT_ID, so EdgeDeploy can never deploy into the wrong AWS account.
     */
    @Bean
    ApplicationRunner awsStartupCheck(AwsProperties properties, AwsCredentialsProvider credentials) {
        List<String> problems = AwsConfigurationValidator.problems(properties);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid AWS configuration (EDGEDEPLOY_AWS_ENABLED=true):\n - "
                    + String.join("\n - ", problems));
        }
        return (ApplicationArguments args) -> {
            try (StsClient sts = StsClient.builder().region(Region.of(properties.region())).credentialsProvider(credentials).build()) {
                String account = sts.getCallerIdentity().account();
                if (!properties.accountId().trim().equals(account)) {
                    throw new IllegalStateException("AWS credentials belong to account " + account
                            + " but AWS_ACCOUNT_ID is " + properties.accountId() + "; refusing to deploy to the wrong account");
                }
                log.info("AWS deployment enabled: account {}, region {}, cluster {}, routing {}, capacity {}", account,
                        properties.region(), properties.ecs().cluster(), properties.routing(), properties.ecs().capacity());
            }
        };
    }
}
