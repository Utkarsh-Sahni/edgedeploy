package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Action;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ActionTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DuplicateListenerException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DuplicateTargetGroupNameException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ProtocolEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Tag;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroupNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetTypeEnum;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Routes each project through the shared Application Load Balancer:
 * {@code http://<alb-dns>:<project port>/ -> listener -> target group -> ECS tasks}.
 *
 * <p><b>Why a port per project:</b> host-based routing ({@code project.edgedeploy.app}) needs a domain and
 * wildcard DNS; path-based routing breaks most single-page apps (absolute asset paths). A dedicated HTTP
 * listener per project serves every app at its root with nothing but the ALB, so the URL EdgeDeploy shows
 * really reaches the app. Limits: plain HTTP and 50 listeners per ALB. Hostname routing with TLS replaces
 * this once a domain is configured (later phase).
 */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "true")
public class AlbRouting {

    /** Target registration port is irrelevant for ECS (it registers the container port); fixed so the group never conflicts. */
    static final int TARGET_GROUP_PORT = 80;

    public record TargetHealthSummary(int healthy, int total, String problem) {
    }

    private final ElasticLoadBalancingV2Client elb;
    private final AwsProperties.Alb alb;
    private final String healthCheckPath;
    private final AwsResourceNames names;
    private volatile String dnsName;

    public AlbRouting(ElasticLoadBalancingV2Client elb, AwsProperties properties, AwsResourceNames names) {
        this.elb = elb;
        this.alb = properties.alb();
        this.healthCheckPath = properties.healthCheck().path();
        this.names = names;
    }

    /** The project's target group (type ip, as Fargate requires), created on first use. */
    public String ensureTargetGroup(UUID projectId) throws DeliveryException {
        String name = names.targetGroup(projectId);
        Optional<TargetGroup> existing = findTargetGroup(name);
        if (existing.isPresent()) {
            return existing.get().targetGroupArn();
        }
        try {
            String arn = elb.createTargetGroup(r -> r
                    .name(name)
                    .protocol(ProtocolEnum.HTTP)
                    .port(TARGET_GROUP_PORT)
                    .vpcId(alb.vpcId())
                    .targetType(TargetTypeEnum.IP)
                    .healthCheckEnabled(true)
                    .healthCheckProtocol(ProtocolEnum.HTTP)
                    .healthCheckPath(healthCheckPath)
                    .healthCheckIntervalSeconds(15)
                    .healthCheckTimeoutSeconds(5)
                    .healthyThresholdCount(2)
                    .unhealthyThresholdCount(3)
                    .matcher(m -> m.httpCode("200-399"))
                    .tags(tag(AwsTags.PROJECT, projectId.toString()), tag(AwsTags.MANAGED, "true")))
                    .targetGroups().getFirst().targetGroupArn();
            // The 300 s default makes every deployment wait 5 minutes for old tasks to drain.
            elb.modifyTargetGroupAttributes(r -> r.targetGroupArn(arn).attributes(a -> a
                    .key("deregistration_delay.timeout_seconds").value(String.valueOf(alb.deregistrationDelay().toSeconds()))));
            return arn;
        } catch (DuplicateTargetGroupNameException race) {
            return findTargetGroup(name).orElseThrow(() -> new DeliveryException("Target group " + name + " disappeared"))
                    .targetGroupArn();
        } catch (SdkException e) {
            throw AwsErrors.translate("elasticloadbalancing:CreateTargetGroup", e);
        }
    }

    /**
     * The project's listener port: the existing listener that forwards to its target group, or the lowest
     * free port in the configured range. Two workers racing for one port is resolved by AWS rejecting the
     * second CreateListener, after which the next free port is tried.
     */
    public int ensureListener(String targetGroupArn) throws DeliveryException {
        try {
            List<Listener> listeners = elb.describeListenersPaginator(r -> r.loadBalancerArn(alb.loadBalancerArn()))
                    .listeners().stream().toList();
            Set<Integer> used = new HashSet<>();
            for (Listener listener : listeners) {
                used.add(listener.port());
                if (listener.defaultActions().stream().anyMatch(action -> forwardsTo(action, targetGroupArn))) {
                    return listener.port();
                }
            }
            for (int port = alb.listenerPortStart(); port <= alb.listenerPortEnd(); port++) {
                if (used.contains(port)) {
                    continue;
                }
                int candidate = port;
                try {
                    elb.createListener(r -> r
                            .loadBalancerArn(alb.loadBalancerArn())
                            .protocol(ProtocolEnum.HTTP)
                            .port(candidate)
                            .defaultActions(Action.builder().type(ActionTypeEnum.FORWARD).targetGroupArn(targetGroupArn).build())
                            .tags(tag(AwsTags.MANAGED, "true")));
                    return candidate;
                } catch (DuplicateListenerException taken) {
                    // another project claimed it concurrently
                }
            }
        } catch (SdkException e) {
            throw AwsErrors.translate("elasticloadbalancing:CreateListener", e);
        }
        throw new DeliveryException("No free load balancer port between " + alb.listenerPortStart() + " and "
                + alb.listenerPortEnd() + " (one per project). Remove unused projects or widen AWS_ALB_LISTENER_PORT_START/END.");
    }

    public String publicUrl(int port) throws DeliveryException {
        if (dnsName == null) {
            try {
                dnsName = elb.describeLoadBalancers(r -> r.loadBalancerArns(alb.loadBalancerArn()))
                        .loadBalancers().getFirst().dnsName().toLowerCase();
            } catch (SdkException e) {
                throw AwsErrors.translate("elasticloadbalancing:DescribeLoadBalancers", e);
            }
        }
        return "http://" + dnsName + ":" + port;
    }

    public TargetHealthSummary targetHealth(String targetGroupArn) throws DeliveryException {
        try {
            List<TargetHealthDescription> targets = elb.describeTargetHealth(r -> r.targetGroupArn(targetGroupArn))
                    .targetHealthDescriptions();
            int healthy = (int) targets.stream().filter(t -> t.targetHealth().state() == TargetHealthStateEnum.HEALTHY).count();
            String problem = targets.stream()
                    .filter(t -> t.targetHealth().state() == TargetHealthStateEnum.UNHEALTHY && t.targetHealth().description() != null)
                    .map(t -> t.targetHealth().description())
                    .findFirst().orElse(null);
            return new TargetHealthSummary(healthy, targets.size(), problem);
        } catch (SdkException e) {
            throw AwsErrors.translate("elasticloadbalancing:DescribeTargetHealth", e);
        }
    }

    private Optional<TargetGroup> findTargetGroup(String name) throws DeliveryException {
        try {
            return elb.describeTargetGroups(r -> r.names(name)).targetGroups().stream().findFirst();
        } catch (TargetGroupNotFoundException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw AwsErrors.translate("elasticloadbalancing:DescribeTargetGroups", e);
        }
    }

    private static boolean forwardsTo(Action action, String targetGroupArn) {
        if (targetGroupArn.equals(action.targetGroupArn())) {
            return true;
        }
        return action.forwardConfig() != null && action.forwardConfig().targetGroups().stream()
                .anyMatch(tg -> targetGroupArn.equals(tg.targetGroupArn()));
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }
}
