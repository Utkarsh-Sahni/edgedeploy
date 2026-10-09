package com.edgedeploy.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.exception.ConflictException;
import com.edgedeploy.repository.DeploymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final UUID ID = UUID.randomUUID();

    @Mock
    DeploymentRepository deployments;

    private DeploymentStateService service() {
        return new DeploymentStateService(deployments, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource({"FAILED,RUNNING", "RUNNING,BUILDING", "QUEUED,RUNNING", "BUILDING,DEPLOYING", "STOPPED,QUEUED"})
    void rejectsTransitionsTheStateMachineForbidsWithoutTouchingTheDatabase(DeploymentStatus from, DeploymentStatus to) {
        assertThatThrownBy(() -> service().transition(ID, from, to))
                .isInstanceOf(IllegalDeploymentTransitionException.class)
                .hasMessageContaining(from + " to " + to);
        verifyNoInteractions(deployments);
    }

    @Test
    void appliesAllowedTransitionAsCompareAndSet() {
        when(deployments.compareAndSetStatus(ID, DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, NOW, null)).thenReturn(1);

        service().transition(ID, DeploymentStatus.QUEUED, DeploymentStatus.BUILDING);

        verify(deployments).compareAndSetStatus(any(), any(), any(), any(), isNull());
    }

    @Test
    void settlingTransitionRecordsCompletionTime() {
        when(deployments.compareAndSetStatus(ID, DeploymentStatus.PUSHING, DeploymentStatus.FAILED, NOW, NOW)).thenReturn(1);

        service().transition(ID, DeploymentStatus.PUSHING, DeploymentStatus.FAILED);
    }

    @Test
    void concurrentChangeIsAConflict() {
        when(deployments.compareAndSetStatus(ID, DeploymentStatus.QUEUED, DeploymentStatus.STOPPED, NOW, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service().transition(ID, DeploymentStatus.QUEUED, DeploymentStatus.STOPPED))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no longer QUEUED");
    }
}
