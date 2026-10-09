package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;

import java.util.Map;

/**
 * Container logs go to {@code /edgedeploy/{projectId}} (streams {@code {deploymentId}/edgedeploy-app/{taskId}}).
 * The group is created by the worker rather than by ECS so it always has a retention policy: groups without one
 * keep logs (and their storage cost) forever.
 */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class CloudWatchLogGroups {

    private final CloudWatchLogsClient logs;
    private final int retentionDays;

    public CloudWatchLogGroups(CloudWatchLogsClient logs, AwsProperties properties) {
        this.logs = logs;
        this.retentionDays = properties.cloudwatch().retentionDays();
    }

    public void ensure(String logGroup, Map<String, String> tags) throws DeliveryException {
        try {
            logs.createLogGroup(r -> r.logGroupName(logGroup).tags(tags));
        } catch (ResourceAlreadyExistsException exists) {
            // fine: created by an earlier deployment
        } catch (SdkException e) {
            throw AwsErrors.translate("logs:CreateLogGroup", e);
        }
        try {
            logs.putRetentionPolicy(r -> r.logGroupName(logGroup).retentionInDays(retentionDays));
        } catch (SdkException e) {
            throw AwsErrors.translate("logs:PutRetentionPolicy", e);
        }
    }
}
