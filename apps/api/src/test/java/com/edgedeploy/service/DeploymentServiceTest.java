package com.edgedeploy.service;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.KafkaTopics;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.contracts.event.DeploymentStatusEvent;
import com.edgedeploy.deployment.DeploymentStateService;
import com.edgedeploy.dto.DeploymentResponse;
import com.edgedeploy.dto.TriggerDeploymentRequest;
import com.edgedeploy.entity.Deployment;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.entity.Framework;
import com.edgedeploy.entity.Project;
import com.edgedeploy.entity.User;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.exception.InvalidRequestException;
import com.edgedeploy.github.GitHubApiException;
import com.edgedeploy.github.GitHubModels;
import com.edgedeploy.github.GitHubService;
import com.edgedeploy.kafka.OutboxWriter;
import com.edgedeploy.repository.DeploymentLogRepository;
import com.edgedeploy.repository.DeploymentRepository;
import com.edgedeploy.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    DeploymentRepository deployments;
    @Mock
    DeploymentLogRepository logs;
    @Mock
    ProjectService projectService;
    @Mock
    DeploymentStateService stateService;
    @Mock
    GitHubService gitHub;
    @Mock
    OutboxWriter outbox;
    @Mock
    PlatformTransactionManager transactionManager;

    private DeploymentService service;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new DeploymentService(deployments, logs, projectService, stateService, gitHub, outbox,
                transactionManager, Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC));
        project = new Project(new User("1", "octocat", "Octo", null, null), "Portfolio", "portfolio",
                "octocat/portfolio", "octocat", 4242L, "main", Framework.NEXTJS, null, null);
        lenient().when(projectService.getOwned(USER_ID, project.getId())).thenReturn(project);
        lenient().when(projectService.getOwnedForUpdate(USER_ID, project.getId())).thenReturn(project);
        lenient().when(deployments.maxNumber(project.getId())).thenReturn(41);
        lenient().when(deployments.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void queuesDeploymentOfTheBranchTipAndPublishesDeploymentRequested() {
        when(gitHub.getBranch(USER_ID, "octocat", "portfolio", "main")).thenReturn(TestFixtures.branch("main"));

        DeploymentResponse response = service.trigger(USER_ID, project.getId(), TriggerDeploymentRequest.branchTip());

        assertThat(response.status()).isEqualTo(DeploymentStatus.QUEUED);
        assertThat(response.commitSha()).isEqualTo(TestFixtures.COMMIT);
        assertThat(response.number()).isEqualTo(42);
        ArgumentCaptor<DeploymentLog> queuedLog = ArgumentCaptor.forClass(DeploymentLog.class);
        verify(logs).save(queuedLog.capture());
        assertThat(queuedLog.getValue().getStep()).isEqualTo(DeploymentStep.QUEUED);
        assertThat(queuedLog.getValue().getMessage()).isEqualTo("Deployment #42 queued for octocat/portfolio@main (3f2a9c1)");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq("Deployment"), eq(response.id()), eq(KafkaTopics.DEPLOYMENT_REQUESTED),
                eq(response.id().toString()), event.capture());
        DeploymentRequestedEvent requested = (DeploymentRequestedEvent) event.getValue();
        assertThat(requested.eventId()).isNotNull();
        assertThat(requested.deploymentId()).isEqualTo(response.id());
        assertThat(requested.projectId()).isEqualTo(project.getId());
        assertThat(requested.repository()).isEqualTo("octocat/portfolio");
        assertThat(requested.branch()).isEqualTo("main");
        assertThat(requested.commitSha()).isEqualTo(TestFixtures.COMMIT);
    }

    @Test
    void resolvesAnAbbreviatedCommitToTheFullSha() {
        when(gitHub.getCommit(USER_ID, "octocat", "portfolio", "3f2a9c1"))
                .thenReturn(new GitHubModels.Commit(TestFixtures.COMMIT));

        DeploymentResponse response = service.trigger(USER_ID, project.getId(), new TriggerDeploymentRequest("3f2a9c1"));

        assertThat(response.commitSha()).isEqualTo(TestFixtures.COMMIT);
    }

    @Test
    void unknownCommitIsAFieldError() {
        when(gitHub.getCommit(any(), anyString(), anyString(), anyString()))
                .thenThrow(new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, 404, "nope"));

        assertThatThrownBy(() -> service.trigger(USER_ID, project.getId(), new TriggerDeploymentRequest("deadbeef")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.field()).isEqualTo("commitSha"));
        verifyNoInteractions(outbox);
    }

    @Test
    void deletedBranchIsAConflictAndNothingIsQueued() {
        when(gitHub.getBranch(any(), anyString(), anyString(), anyString()))
                .thenThrow(new GitHubApiException(GitHubApiException.Kind.NOT_FOUND, 404, "gone"));

        assertThatThrownBy(() -> service.trigger(USER_ID, project.getId(), TriggerDeploymentRequest.branchTip()))
                .isInstanceOf(ConflictException.class);
        verify(deployments, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void cancelIsRefusedOnceTheRolloutHasStarted() {
        Deployment deployment = Deployment.queue(project, 1, TestFixtures.COMMIT);
        org.springframework.test.util.ReflectionTestUtils.setField(deployment, "status", DeploymentStatus.DEPLOYING);
        when(deployments.findOwned(deployment.getId(), USER_ID)).thenReturn(Optional.of(deployment));

        assertThatThrownBy(() -> service.cancel(USER_ID, deployment.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already rolling out");
        verifyNoInteractions(stateService);
    }

    @Test
    void cancelQueuedDeploymentTransitionsToStoppedAndAnnouncesIt() {
        Deployment deployment = Deployment.queue(project, 1, TestFixtures.COMMIT);
        when(deployments.findOwned(deployment.getId(), USER_ID)).thenReturn(Optional.of(deployment));

        service.cancel(USER_ID, deployment.getId());

        verify(stateService).transition(deployment.getId(), DeploymentStatus.QUEUED, DeploymentStatus.STOPPED);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq("Deployment"), eq(deployment.getId()), eq(KafkaTopics.DEPLOYMENT_STATUS), anyString(),
                event.capture());
        assertThat(((DeploymentStatusEvent) event.getValue()).status()).isEqualTo(DeploymentStatus.STOPPED);
    }
}
