package com.edgedeploy.worker.aws;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.worker.deployment.DeploymentStore;
import com.edgedeploy.worker.kafka.DeploymentEventPublisher;
import com.edgedeploy.worker.logging.DeploymentLogService;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class PublicIpAddressRefresherTest {

    private static final UUID DEPLOYMENT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final String TASK_DEFINITION = "arn:aws:ecs:ap-south-1:123456789012:task-definition/edgedeploy-p:7";

    private final DeploymentStore store = mock(DeploymentStore.class);
    private final EcsClient ecs = mock(EcsClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final TaskAddresses addresses = mock(TaskAddresses.class);
    private final DeploymentLogService logs = mock(DeploymentLogService.class);
    private final DeploymentEventPublisher events = mock(DeploymentEventPublisher.class);
    private final PublicIpAddressRefresher refresher =
            new PublicIpAddressRefresher(store, ecs, addresses, logs, events, AwsTestProperties.budget());

    @Test
    void movesTheUrlToTheTaskThatReplacedTheOriginalOne() throws Exception {
        runningTasks(task("task-2", TASK_DEFINITION));
        when(addresses.publicIp(any())).thenReturn(Optional.of("13.200.1.2"));
        when(store.updateLiveAddress(DEPLOYMENT, "task-1", "task-2", "http://13.200.1.2:3000")).thenReturn(true);

        assertThat(refresher.refresh(live("task-1", "http://3.91.10.20:3000"))).isTrue();

        verify(events).statusChanged(eq(DEPLOYMENT), eq(PROJECT), eq(DeploymentStatus.RUNNING), eq("http://13.200.1.2:3000"), any());
        verify(logs).append(eq(DEPLOYMENT), anyList());
    }

    @Test
    void leavesTheUrlAloneWhileTheSameTaskServes() throws Exception {
        runningTasks(task("task-1", TASK_DEFINITION));

        assertThat(refresher.refresh(live("task-1", "http://3.91.10.20:3000"))).isFalse();

        verifyNoInteractions(addresses, events);
    }

    @Test
    void ignoresTasksOfANewerDeploymentStillRollingOut() throws Exception {
        runningTasks(task("task-9", TASK_DEFINITION.replace(":7", ":8")));

        assertThat(refresher.refresh(live("task-1", "http://3.91.10.20:3000"))).isFalse();

        verify(store, never()).updateLiveAddress(any(), any(), any(), any());
    }

    @Test
    void loadBalancerUrlsAreNotTouched() throws Exception {
        assertThat(refresher.refresh(live("task-1", "http://edgedeploy-123.elb.amazonaws.com:10003"))).isFalse();

        verifyNoInteractions(addresses, events);
    }

    @Test
    void findsTheTasksNetworkInterface() {
        Task task = Task.builder().attachments(Attachment.builder().type("ElasticNetworkInterface")
                .details(KeyValuePair.builder().name("subnetId").value("subnet-1").build(),
                        KeyValuePair.builder().name("networkInterfaceId").value("eni-0abc").build())
                .build()).build();

        assertThat(TaskAddresses.networkInterfaceId(task)).contains("eni-0abc");
        assertThat(TaskAddresses.networkInterfaceId(Task.builder().build())).isEmpty();
    }

    private static DeploymentStore.LiveDeployment live(String taskArn, String url) {
        return new DeploymentStore.LiveDeployment(DEPLOYMENT, PROJECT, "edgedeploy", "edgedeploy-p", TASK_DEFINITION, taskArn, url);
    }

    private static Task task(String arn, String taskDefinition) {
        return Task.builder().taskArn(arn).taskDefinitionArn(taskDefinition).lastStatus("RUNNING").build();
    }

    private void runningTasks(Task... tasks) {
        doReturn(ListTasksResponse.builder().taskArns(java.util.Arrays.stream(tasks).map(Task::taskArn).toList()).build())
                .when(ecs).listTasks(any(ListTasksRequest.class));
        doReturn(DescribeTasksResponse.builder().tasks(List.of(tasks)).build()).when(ecs).describeTasks(any(DescribeTasksRequest.class));
    }
}
