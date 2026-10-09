package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.Task;

import java.util.Optional;

/**
 * Public IPv4 address of a running Fargate task (budget mode, where that address is the application's URL).
 * ECS only reports the task's network interface id; the public IP belongs to the interface, so EC2 is asked.
 */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class TaskAddresses {

    private final Ec2Client ec2;

    public TaskAddresses(Ec2Client ec2) {
        this.ec2 = ec2;
    }

    /** @return the address, or empty while the task has none yet (still provisioning) */
    public Optional<String> publicIp(Task task) throws DeliveryException {
        Optional<String> eni = networkInterfaceId(task);
        if (eni.isEmpty()) {
            return Optional.empty();
        }
        try {
            return ec2.describeNetworkInterfaces(r -> r.networkInterfaceIds(eni.get())).networkInterfaces().stream()
                    .findFirst()
                    .map(NetworkInterface::association)
                    .map(association -> association.publicIp())
                    .filter(ip -> !ip.isBlank());
        } catch (SdkException e) {
            throw AwsErrors.translate("ec2:DescribeNetworkInterfaces", e);
        }
    }

    static Optional<String> networkInterfaceId(Task task) {
        return task.attachments().stream()
                .filter(attachment -> "ElasticNetworkInterface".equals(attachment.type()))
                .map(Attachment::details)
                .flatMap(details -> details.stream())
                .filter(detail -> "networkInterfaceId".equals(detail.name()))
                .map(KeyValuePair::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
