package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.worker.kafka.InvalidDeploymentEventException;
import com.edgedeploy.worker.logging.DeploymentLogService;
import com.edgedeploy.worker.service.DeploymentStatusService;
import com.edgedeploy.worker.support.TestProperties;
import com.edgedeploy.worker.workspace.Workspace;
import com.edgedeploy.worker.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentProcessorTest {

    private static final UUID DEPLOYMENT = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final DeploymentRequestedEvent EVENT = new DeploymentRequestedEvent(UUID.randomUUID(), DEPLOYMENT, PROJECT,
            "octocat/app", "main", "a83f12c0a83f12c0a83f12c0a83f12c0a83f12c0", Instant.now());
    private static final ClaimedDeployment CLAIMED = new ClaimedDeployment(DEPLOYMENT, PROJECT, 7, "octocat/app", "main",
            EVENT.commitSha(), "UNKNOWN", null, null);

    @TempDir
    Path root;

    @Mock
    DeploymentStore store;
    @Mock
    DeploymentStatusService status;
    @Mock
    DeploymentPipeline pipeline;
    @Mock
    WorkspaceManager workspaces;
    @Mock
    DeploymentLogService logs;
    @Mock
    CancellationProbe cancellation;

    private DeploymentProcessor processor;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        processor = new DeploymentProcessor(store, status, pipeline, workspaces, logs, cancellation,
                TestProperties.create(root, "https://github.com"), Clock.systemUTC());
        workspace = new Workspace(root.resolve(DEPLOYMENT.toString()));
        lenient().when(workspaces.create(DEPLOYMENT)).thenReturn(workspace);
        lenient().when(cancellation.forDeployment(DEPLOYMENT)).thenReturn(() -> false);
    }

    @Test
    void claimsRunsAndAlwaysCleansUp() throws Exception {
        queued();

        processor.handle(EVENT);

        verify(pipeline).run(any());
        verify(workspaces).delete(workspace);
        verify(status, never()).fail(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentStatus.class, names = {"BUILDING", "IMAGE_BUILT", "RUNNING", "FAILED", "STOPPED"})
    void duplicateOrLateEventsDoNotRebuild(DeploymentStatus current) {
        when(store.findSummary(DEPLOYMENT)).thenReturn(Optional.of(new DeploymentStore.DeploymentSummary(PROJECT, current)));

        processor.handle(EVENT);

        verify(status, never()).claim(any(), anyString());
        verifyNoInteractions(pipeline, workspaces);
    }

    @Test
    void losingTheClaimRaceIsHarmless() {
        when(store.findSummary(DEPLOYMENT)).thenReturn(Optional.of(new DeploymentStore.DeploymentSummary(PROJECT, DeploymentStatus.QUEUED)));
        when(status.claim(eq(DEPLOYMENT), anyString())).thenReturn(Optional.empty());

        processor.handle(EVENT);

        verifyNoInteractions(pipeline, workspaces);
    }

    @Test
    void deletedDeploymentIsIgnored() {
        when(store.findSummary(DEPLOYMENT)).thenReturn(Optional.empty());

        assertThatCode(() -> processor.handle(EVENT)).doesNotThrowAnyException();
        verifyNoInteractions(pipeline);
    }

    @Test
    void eventForTheWrongProjectIsRejectedAsInvalid() {
        when(store.findSummary(DEPLOYMENT)).thenReturn(Optional.of(
                new DeploymentStore.DeploymentSummary(UUID.randomUUID(), DeploymentStatus.QUEUED)));

        assertThatThrownBy(() -> processor.handle(EVENT)).isInstanceOf(InvalidDeploymentEventException.class);
        verifyNoInteractions(pipeline);
    }

    @Test
    void pipelineFailureMarksTheDeploymentFailedAndCleansUp() throws Exception {
        queued();
        doThrow(new DeploymentFailure(DeploymentStep.IMAGE, "Docker build failed: npm error code E404")).when(pipeline).run(any());

        processor.handle(EVENT);

        verify(status).fail(DEPLOYMENT, PROJECT, DeploymentStatus.BUILDING, DeploymentStep.IMAGE, "Docker build failed: npm error code E404");
        verify(workspaces).delete(workspace);
    }

    @Test
    void unexpectedErrorsAreContainedSoTheWorkerKeepsConsuming() throws Exception {
        queued();
        doThrow(new IllegalStateException("NPE in /home/worker with password=secret")).when(pipeline).run(any());

        assertThatCode(() -> processor.handle(EVENT)).doesNotThrowAnyException();

        verify(status).fail(eq(DEPLOYMENT), eq(PROJECT), eq(DeploymentStatus.BUILDING), any(),
                eq("Internal error in the build worker. Try deploying again."));
        verify(workspaces).delete(workspace);
    }

    @Test
    void cancellationIsNotAFailure() throws Exception {
        queued();
        doThrow(new DeploymentCancelledException()).when(pipeline).run(any());

        processor.handle(EVENT);

        verify(status, never()).fail(any(), any(), any(), any(), any());
        verify(logs).warn(eq(DEPLOYMENT), contains("cancelled"));
        verify(workspaces).delete(workspace);
    }

    private void queued() {
        when(store.findSummary(DEPLOYMENT)).thenReturn(Optional.of(new DeploymentStore.DeploymentSummary(PROJECT, DeploymentStatus.QUEUED)));
        when(status.claim(eq(DEPLOYMENT), anyString())).thenReturn(Optional.of(CLAIMED));
    }
}
