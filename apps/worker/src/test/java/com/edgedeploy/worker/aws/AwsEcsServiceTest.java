package com.edgedeploy.worker.aws;

import com.edgedeploy.worker.delivery.DeliveryException;
import com.edgedeploy.worker.delivery.DeploymentTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest;
import software.amazon.awssdk.services.ecs.model.CreateServiceResponse;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DeploymentRolloutState;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;
import software.amazon.awssdk.services.ecs.model.UpdateServiceResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class AwsEcsServiceTest {

    private static final UUID PROJECT = UUID.fromString("8f42d1a0-5b6c-4d7e-8f90-a1b2c3d4e5f6");
    private static final UUID DEPLOYMENT = UUID.fromString("0d3c2b1a-0000-4000-8000-000000000042");
    private static final String SERVICE = "edgedeploy-" + PROJECT;
    private static final String TASK_DEFINITION = "arn:aws:ecs:ap-south-1:123456789012:task-definition/edgedeploy-" + PROJECT + ":7";
    private static final String PREVIOUS = "arn:aws:ecs:ap-south-1:123456789012:task-definition/edgedeploy-" + PROJECT + ":6";
    private static final String TARGET_GROUP = "arn:aws:elasticloadbalancing:ap-south-1:123456789012:targetgroup/ed-x/1";
    private static final String URL = "http://edgedeploy-123.ap-south-1.elb.amazonaws.com:10003";
    private static final String IMAGE = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/edgedeploy/" + PROJECT + "@sha256:abc";

    private final EcsClient ecs = mock(EcsClient.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final AlbRouting routing = mock(AlbRouting.class);
    private final CloudWatchLogGroups logGroups = mock(CloudWatchLogGroups.class);
    private final SsmEnvironmentStore ssm = mock(SsmEnvironmentStore.class);
    private final HttpHealthProbe probe = mock(HttpHealthProbe.class);
    private final TaskAddresses addresses = mock(TaskAddresses.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
    private final List<String> progress = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        when(routing.ensureTargetGroup(PROJECT)).thenReturn(TARGET_GROUP);
        when(routing.ensureListener(TARGET_GROUP)).thenReturn(10003);
        when(routing.publicUrl(10003)).thenReturn(URL);
        when(ssm.sync(eq(PROJECT), anyMap())).thenAnswer(invocation -> {
            Map<String, String> values = invocation.getArgument(1);
            Map<String, String> arns = new java.util.TreeMap<>();
            values.keySet().forEach(k -> arns.put(k, "arn:aws:ssm:ap-south-1:123456789012:parameter/edgedeploy/" + PROJECT + "/env/" + k));
            return arns;
        });
        doReturn(RegisterTaskDefinitionResponse.builder().taskDefinition(TaskDefinition.builder()
                .taskDefinitionArn(TASK_DEFINITION).family("edgedeploy-" + PROJECT).revision(7).build()).build())
                .when(ecs).registerTaskDefinition(any(RegisterTaskDefinitionRequest.class));
        doReturn(CreateServiceResponse.builder().build()).when(ecs).createService(any(CreateServiceRequest.class));
        doReturn(UpdateServiceResponse.builder().build()).when(ecs).updateService(any(UpdateServiceRequest.class));
    }

    private AwsEcsService service(AwsProperties properties) {
        return new AwsEcsService(ecs, routing, logGroups, ssm, new AwsResourceNames(properties), properties, probe,
                addresses, clock::advance, clock);
    }

    // ---- start ---------------------------------------------------------------------------------

    @Test
    void firstDeploymentRegistersATaskDefinitionAndCreatesTheFargateService() throws Exception {
        describeServicesReturns(); // no service yet

        DeploymentTargetService.Rollout rollout = service(AwsTestProperties.create()).start(release(), progress::add);

        ArgumentCaptor<RegisterTaskDefinitionRequest> registered = ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecs).registerTaskDefinition(registered.capture());
        ContainerDefinition container = registered.getValue().containerDefinitions().getFirst();
        assertThat(registered.getValue().family()).isEqualTo("edgedeploy-" + PROJECT);
        assertThat(registered.getValue().cpu()).isEqualTo("256");
        assertThat(registered.getValue().memory()).isEqualTo("512");
        assertThat(container.image()).isEqualTo(IMAGE);
        assertThat(container.portMappings().getFirst().containerPort()).isEqualTo(3000);
        // Plain metadata in the environment; user variables only as SSM references, never values.
        assertThat(container.environment()).anyMatch(kv -> kv.name().equals("PORT") && kv.value().equals("3000"))
                .anyMatch(kv -> kv.name().equals("EDGEDEPLOY_DEPLOYMENT_ID"))
                .noneMatch(kv -> kv.name().equals("API_URL") || kv.name().equals("SECRET"));
        assertThat(container.secrets()).extracting(s -> s.name()).containsExactly("API_URL", "SECRET");
        assertThat(container.toString()).doesNotContain("hunter2");
        verify(logGroups).ensure(eq("/edgedeploy/" + PROJECT), anyMap());

        ArgumentCaptor<CreateServiceRequest> created = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(created.capture());
        CreateServiceRequest request = created.getValue();
        assertThat(request.serviceName()).isEqualTo(SERVICE);
        assertThat(request.cluster()).isEqualTo("edgedeploy");
        assertThat(request.launchType()).isEqualTo(LaunchType.FARGATE);
        assertThat(request.desiredCount()).isEqualTo(1);
        assertThat(request.taskDefinition()).isEqualTo(TASK_DEFINITION);
        assertThat(request.networkConfiguration().awsvpcConfiguration().subnets()).hasSize(2);
        assertThat(request.networkConfiguration().awsvpcConfiguration().securityGroups()).containsExactly("sg-0123456789abcdef0");
        assertThat(request.networkConfiguration().awsvpcConfiguration().assignPublicIp()).isEqualTo(AssignPublicIp.ENABLED);
        assertThat(request.loadBalancers()).singleElement().satisfies(lb -> {
            assertThat(lb.targetGroupArn()).isEqualTo(TARGET_GROUP);
            assertThat(lb.containerName()).isEqualTo("edgedeploy-app");
            assertThat(lb.containerPort()).isEqualTo(3000);
        });
        assertThat(request.deploymentConfiguration().deploymentCircuitBreaker().rollback()).isTrue();
        assertThat(request.clientToken()).isEqualTo(DEPLOYMENT.toString());
        assertThat(request.tags()).anyMatch(t -> t.key().equals(AwsTags.PROJECT) && t.value().equals(PROJECT.toString()));

        assertThat(rollout).isEqualTo(new DeploymentTargetService.Rollout("edgedeploy", SERVICE, TASK_DEFINITION, URL,
                TARGET_GROUP, 3000, clock.instant()));
    }

    @Test
    void laterDeploymentsUpdateTheSameService() throws Exception {
        describeServicesReturns(service("ACTIVE", PROJECT).toBuilder()
                .loadBalancers(software.amazon.awssdk.services.ecs.model.LoadBalancer.builder().targetGroupArn(TARGET_GROUP).build())
                .build());

        service(AwsTestProperties.create()).start(release(), progress::add);

        ArgumentCaptor<UpdateServiceRequest> updated = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs).updateService(updated.capture());
        assertThat(updated.getValue().service()).isEqualTo(SERVICE);
        assertThat(updated.getValue().taskDefinition()).isEqualTo(TASK_DEFINITION);
        verify(ecs, never()).createService(any(CreateServiceRequest.class));
    }

    @Test
    void refusesToTouchAServiceOwnedBySomethingElse() {
        describeServicesReturns(service("ACTIVE", UUID.randomUUID()));

        assertThatThrownBy(() -> service(AwsTestProperties.create()).start(release(), progress::add))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("not managed by EdgeDeploy for this project");
        verify(ecs, never()).updateService(any(UpdateServiceRequest.class));
    }

    @Test
    void environmentModeKeepsVariablesInTheTaskDefinition() throws Exception {
        describeServicesReturns();

        service(AwsTestProperties.create(AwsProperties.SecretsMode.ENVIRONMENT)).start(release(), progress::add);

        ArgumentCaptor<RegisterTaskDefinitionRequest> registered = ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecs).registerTaskDefinition(registered.capture());
        assertThat(registered.getValue().containerDefinitions().getFirst().environment())
                .anyMatch(kv -> kv.name().equals("API_URL") && kv.value().equals("https://api.example.com"));
        verifyNoInteractions(ssm);
    }

    // ---- awaitStable ---------------------------------------------------------------------------

    @Test
    void waitsForTheRolloutToCompleteAndReturnsTheRunningTask() throws Exception {
        describeServicesReturns(withDeployment(DeploymentRolloutState.IN_PROGRESS, 0), withDeployment(DeploymentRolloutState.COMPLETED, 1));
        tasks(DesiredStatus.STOPPED);
        tasks(DesiredStatus.RUNNING, Task.builder().taskArn("task-1").taskDefinitionArn(TASK_DEFINITION).lastStatus("RUNNING").build());

        DeploymentTargetService.Running running = service(AwsTestProperties.create())
                .awaitStable(rollout(), Duration.ofMinutes(10), progress::add);

        assertThat(running).isEqualTo(new DeploymentTargetService.Running("task-1", URL));
        assertThat(progress).contains("ECS tasks running 0/1, pending 1", "ECS deployment stabilized (1/1 tasks running)");
    }

    @Test
    void crashingTasksFailTheDeploymentWithTheirReason() {
        describeServicesReturns(withDeployment(DeploymentRolloutState.IN_PROGRESS, 0));
        Task crashed = Task.builder().taskArn("t1").taskDefinitionArn(TASK_DEFINITION)
                .stoppedReason("Essential container in task exited")
                .containers(Container.builder().exitCode(1).build()).build();
        Task crashedAgain = crashed.toBuilder().taskArn("t2").build();
        tasks(DesiredStatus.STOPPED, crashed, crashedAgain);

        assertThatThrownBy(() -> service(AwsTestProperties.create()).awaitStable(rollout(), Duration.ofMinutes(10), progress::add))
                .isInstanceOf(DeliveryException.class)
                .hasMessage("Task stopped unexpectedly: Essential container in task exited (exit code 1)");
    }

    @Test
    void failedRolloutIsReported() {
        describeServicesReturns(withDeployment(DeploymentRolloutState.FAILED, 0, "ECS deployment circuit breaker: tasks failed to start."));
        tasks(DesiredStatus.STOPPED);

        assertThatThrownBy(() -> service(AwsTestProperties.create()).awaitStable(rollout(), Duration.ofMinutes(10), progress::add))
                .hasMessage("ECS deployment failed: ECS deployment circuit breaker: tasks failed to start.");
    }

    @Test
    void rolloutThatNeverStabilizesTimesOut() {
        describeServicesReturns(withDeployment(DeploymentRolloutState.IN_PROGRESS, 0));
        tasks(DesiredStatus.STOPPED);

        assertThatThrownBy(() -> service(AwsTestProperties.create()).awaitStable(rollout(), Duration.ofSeconds(60), progress::add))
                .isInstanceOf(DeliveryException.class)
                .hasMessageStartingWith("ECS service failed to stabilize within 60s");
    }

    // ---- verifyHealth / restore ------------------------------------------------------------------

    @Test
    void healthyOnlyWhenTargetsAreHealthyAndTheUrlAnswers() throws Exception {
        when(routing.targetHealth(TARGET_GROUP)).thenReturn(new AlbRouting.TargetHealthSummary(0, 1, null),
                new AlbRouting.TargetHealthSummary(1, 1, null));
        when(probe.status(any(), any())).thenThrow(new IOException("connect")).thenReturn(200);

        service(AwsTestProperties.create()).verifyHealth(rollout(), Duration.ofMinutes(3), progress::add);

        assertThat(progress).contains("Load balancer targets healthy (1/1)", "Health check passed: HTTP 200");
    }

    @Test
    void failingApplicationFailsTheHealthCheck() throws Exception {
        when(routing.targetHealth(TARGET_GROUP)).thenReturn(new AlbRouting.TargetHealthSummary(1, 1, null));
        when(probe.status(any(), any())).thenReturn(502);

        assertThatThrownBy(() -> service(AwsTestProperties.create()).verifyHealth(rollout(), Duration.ofSeconds(30), progress::add))
                .isInstanceOf(DeliveryException.class)
                .hasMessage("Health check failed: " + URL + "/ answered HTTP 502");
    }

    @Test
    void restoreRollsBackToThePreviousVersionOrStopsAFirstDeployment() {
        AwsEcsService service = service(AwsTestProperties.create());

        service.restore(rollout(), PREVIOUS, progress::add);
        service.restore(rollout(), null, progress::add);

        ArgumentCaptor<UpdateServiceRequest> updates = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs, org.mockito.Mockito.times(2)).updateService(updates.capture());
        assertThat(updates.getAllValues().get(0).taskDefinition()).isEqualTo(PREVIOUS);
        assertThat(updates.getAllValues().get(1).desiredCount()).isZero();
    }

    // ---- budget mode (AWS_ROUTING_MODE=public-ip, AWS_ECS_CAPACITY=fargate-spot) -------------------

    @Test
    void budgetModeCreatesAServiceWithoutLoadBalancerOnFargateSpot() throws Exception {
        describeServicesReturns();

        DeploymentTargetService.Rollout rollout = service(AwsTestProperties.budget()).start(release("amd64"), progress::add);

        ArgumentCaptor<CreateServiceRequest> created = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(created.capture());
        CreateServiceRequest request = created.getValue();
        assertThat(request.loadBalancers()).isEmpty();
        assertThat(request.healthCheckGracePeriodSeconds()).isNull(); // ECS rejects it without a load balancer
        assertThat(request.launchType()).isNull(); // mutually exclusive with a capacity provider strategy
        assertThat(request.capacityProviderStrategy()).singleElement()
                .satisfies(item -> assertThat(item.capacityProvider()).isEqualTo("FARGATE_SPOT"));
        assertThat(request.networkConfiguration().awsvpcConfiguration().assignPublicIp()).isEqualTo(AssignPublicIp.ENABLED);
        verifyNoInteractions(routing);
        assertThat(rollout.url()).isNull();
        assertThat(rollout.targetGroupArn()).isNull();
        assertThat(rollout.containerPort()).isEqualTo(3000);
    }

    @Test
    void arm64ImagesRunOnOnDemandFargateBecauseSpotDoesNotSupportThem() throws Exception {
        describeServicesReturns();

        service(AwsTestProperties.budget()).start(release("arm64"), progress::add);

        ArgumentCaptor<CreateServiceRequest> created = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(created.capture());
        assertThat(created.getValue().capacityProviderStrategy()).singleElement()
                .satisfies(item -> assertThat(item.capacityProvider()).isEqualTo("FARGATE"));
        assertThat(progress).anyMatch(line -> line.startsWith("Fargate Spot does not run ARM64 images"));
    }

    @Test
    void budgetModeUpdatesForceANewDeploymentOnTheChosenCapacity() throws Exception {
        describeServicesReturns(service("ACTIVE", PROJECT));

        service(AwsTestProperties.budget()).start(release("amd64"), progress::add);

        ArgumentCaptor<UpdateServiceRequest> updated = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs).updateService(updated.capture());
        assertThat(updated.getValue().loadBalancers()).isEmpty();
        assertThat(updated.getValue().capacityProviderStrategy()).singleElement()
                .satisfies(item -> assertThat(item.capacityProvider()).isEqualTo("FARGATE_SPOT"));
        assertThat(updated.getValue().forceNewDeployment()).isTrue();
    }

    @Test
    void refusesToSwitchAnExistingServiceBetweenRoutingModes() {
        describeServicesReturns(service("ACTIVE", PROJECT).toBuilder()
                .loadBalancers(software.amazon.awssdk.services.ecs.model.LoadBalancer.builder().targetGroupArn(TARGET_GROUP).build())
                .build());

        assertThatThrownBy(() -> service(AwsTestProperties.budget()).start(release("amd64"), progress::add))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("other routing mode");
        verify(ecs, never()).updateService(any(UpdateServiceRequest.class));
    }

    @Test
    void refusesToMoveAnOnDemandServiceToSpotImplicitly() {
        describeServicesReturns(service("ACTIVE", PROJECT).toBuilder().launchType(LaunchType.FARGATE).build());

        assertThatThrownBy(() -> service(AwsTestProperties.budget()).start(release("amd64"), progress::add))
                .hasMessageContaining("AWS_ECS_CAPACITY changed");
    }

    @Test
    void budgetModeReportsTheTasksPublicIpAsTheUrl() throws Exception {
        describeServicesReturns(withDeployment(DeploymentRolloutState.COMPLETED, 1));
        Task task = Task.builder().taskArn("task-1").taskDefinitionArn(TASK_DEFINITION).lastStatus("RUNNING").build();
        tasks(DesiredStatus.RUNNING, task);
        when(addresses.publicIp(any())).thenReturn(java.util.Optional.empty(), java.util.Optional.of("3.91.10.20"));

        DeploymentTargetService.Running running = service(AwsTestProperties.budget())
                .awaitStable(budgetRollout(null), Duration.ofMinutes(10), progress::add);

        assertThat(running).isEqualTo(new DeploymentTargetService.Running("task-1", "http://3.91.10.20:3000"));
        assertThat(progress).contains("Waiting for the task's public IP address",
                "ECS deployment stabilized; the application is at http://3.91.10.20:3000");
    }

    @Test
    void budgetModeHealthCheckGoesStraightToTheApplication() throws Exception {
        when(probe.status(any(), any())).thenReturn(200);

        service(AwsTestProperties.budget()).verifyHealth(budgetRollout("http://3.91.10.20:3000"), Duration.ofMinutes(3), progress::add);

        verify(probe).status(eq(java.net.URI.create("http://3.91.10.20:3000/")), any());
        verifyNoInteractions(routing);
        assertThat(progress).contains("Health check passed: HTTP 200").doesNotContain("Checking load balancer target health");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private DeploymentTargetService.Release release() {
        return release("arm64");
    }

    private DeploymentTargetService.Release release(String architecture) {
        return new DeploymentTargetService.Release(DEPLOYMENT, PROJECT, 42, "a83f12c0a83f12c0a83f12c0a83f12c0a83f12c0", IMAGE,
                architecture, 3000, Map.of("API_URL", "https://api.example.com", "SECRET", "hunter2"));
    }

    private DeploymentTargetService.Rollout budgetRollout(String url) {
        return new DeploymentTargetService.Rollout("edgedeploy", SERVICE, TASK_DEFINITION, url, null, 3000, clock.instant());
    }

    private DeploymentTargetService.Rollout rollout() {
        return new DeploymentTargetService.Rollout("edgedeploy", SERVICE, TASK_DEFINITION, URL, TARGET_GROUP, 3000, clock.instant());
    }

    private void describeServicesReturns(Service... sequence) {
        if (sequence.length == 0) {
            doReturn(DescribeServicesResponse.builder().build()).when(ecs).describeServices(any(DescribeServicesRequest.class));
            return;
        }
        List<DescribeServicesResponse> responses = java.util.Arrays.stream(sequence)
                .map(s -> DescribeServicesResponse.builder().services(s).build()).toList();
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> responses.get(Math.min(call.getAndIncrement(), responses.size() - 1)))
                .when(ecs).describeServices(any(DescribeServicesRequest.class));
    }

    private static Service service(String status, UUID owner) {
        return Service.builder().serviceName(SERVICE).status(status)
                .tags(Tag.builder().key(AwsTags.PROJECT).value(owner.toString()).build()).build();
    }

    private static Service withDeployment(DeploymentRolloutState state, int running) {
        return withDeployment(state, running, null);
    }

    private static Service withDeployment(DeploymentRolloutState state, int running, String reason) {
        return service("ACTIVE", PROJECT).toBuilder().deployments(Deployment.builder().taskDefinition(TASK_DEFINITION)
                .rolloutState(state).rolloutStateReason(reason).runningCount(running).desiredCount(1).pendingCount(1 - running)
                .build()).build();
    }

    private void tasks(DesiredStatus status, Task... tasks) {
        List<String> arns = java.util.Arrays.stream(tasks).map(Task::taskArn).toList();
        doAnswer(invocation -> {
            ListTasksRequest request = invocation.getArgument(0);
            return request.desiredStatus() == status ? ListTasksResponse.builder().taskArns(arns).build() : null;
        }).when(ecs).listTasks(any(ListTasksRequest.class));
        if (tasks.length > 0) {
            doReturn(DescribeTasksResponse.builder().tasks(tasks).build()).when(ecs).describeTasks(any(DescribeTasksRequest.class));
        }
        if (status == DesiredStatus.RUNNING) {
            doAnswer(invocation -> {
                ListTasksRequest request = invocation.getArgument(0);
                return ListTasksResponse.builder().taskArns(request.desiredStatus() == DesiredStatus.RUNNING ? arns : List.of()).build();
            }).when(ecs).listTasks(any(ListTasksRequest.class));
        }
    }

    /** A clock moved forward by the service's own waits, so timeouts are tested without sleeping. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration.isZero() ? Duration.ofSeconds(1) : duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
