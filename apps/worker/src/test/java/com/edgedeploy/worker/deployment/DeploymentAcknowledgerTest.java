package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.worker.kafka.DeploymentEventPublisher;
import com.edgedeploy.worker.logging.DeploymentLogService;
import com.edgedeploy.worker.kafka.InvalidDeploymentEventException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeploymentAcknowledgerTest {

    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final DeploymentRequestedEvent EVENT = new DeploymentRequestedEvent(UUID.randomUUID(), DEPLOYMENT_ID,
            PROJECT_ID, "octocat/portfolio", "main", "3f2a9c1d4e5b6a7980f1e2d3c4b5a69788776655", Instant.now());

    @Mock
    DeploymentStore store;
    @Mock
    ProcessedEventLedger ledger;
    @Mock
    DeploymentLogService logs;
    @Mock
    DeploymentEventPublisher events;

    @Test
    void firstDeliveryIsRecordedAndLoggedWithoutChangingState() {
        when(store.findSummary(DEPLOYMENT_ID)).thenReturn(Optional.of(
                new DeploymentStore.DeploymentSummary(PROJECT_ID, DeploymentStatus.QUEUED)));
        when(ledger.recordIfFirst(DeploymentAcknowledger.CONSUMER, EVENT.eventId(), DEPLOYMENT_ID)).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            acknowledger().handle(EVENT);

            verify(logs).append(eq(DEPLOYMENT_ID), argThat(entries -> entries.size() == 1
                    && entries.getFirst().message().contains("received by worker for octocat/portfolio@3f2a9c1")));
            verifyNoInteractions(events); // nothing announced before the transaction commits
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(events).statusChanged(DEPLOYMENT_ID, PROJECT_ID, DeploymentStatus.QUEUED, null, "Received by worker");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(store, never()).transition(any(), any(), any());
        verify(store, never()).claim(any());
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        when(store.findSummary(DEPLOYMENT_ID)).thenReturn(Optional.of(
                new DeploymentStore.DeploymentSummary(PROJECT_ID, DeploymentStatus.QUEUED)));
        when(ledger.recordIfFirst(DeploymentAcknowledger.CONSUMER, EVENT.eventId(), DEPLOYMENT_ID)).thenReturn(false);

        acknowledger().handle(EVENT);

        verifyNoInteractions(logs, events);
    }

    @Test
    void missingDeploymentIsIgnored() {
        when(store.findSummary(DEPLOYMENT_ID)).thenReturn(Optional.empty());

        acknowledger().handle(EVENT);

        verifyNoInteractions(ledger, logs);
    }

    @Test
    void projectMismatchIsRejectedAsInvalidEvent() {
        when(store.findSummary(DEPLOYMENT_ID)).thenReturn(Optional.of(
                new DeploymentStore.DeploymentSummary(UUID.randomUUID(), DeploymentStatus.QUEUED)));

        assertThatThrownBy(() -> acknowledger().handle(EVENT)).isInstanceOf(InvalidDeploymentEventException.class);
        verifyNoInteractions(ledger, logs);
    }

    private DeploymentAcknowledger acknowledger() {
        return new DeploymentAcknowledger(store, ledger, logs, events);
    }
}
