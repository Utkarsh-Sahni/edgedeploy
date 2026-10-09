package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.worker.kafka.DeploymentEventPublisher;
import com.edgedeploy.worker.logging.DeploymentLogService;
import com.edgedeploy.worker.kafka.InvalidDeploymentEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * {@code WORKER_MODE=acknowledge}: prove the API -> Postgres -> Kafka -> worker path without doing any
 * deployment work (the Phase 2 behaviour; handy where git/Docker are unavailable).
 *
 * <ol>
 *   <li>Verify the deployment exists and matches the event.</li>
 *   <li>Record the event in the idempotency ledger; a duplicate delivery stops here.</li>
 *   <li>Append a timeline entry, visible via {@code GET /api/deployments/{id}/logs}.</li>
 *   <li>Publish an unchanged-status notification so open dashboards refresh and show the entry.</li>
 * </ol>
 * The status is deliberately left untouched (QUEUED), so duplicates cannot corrupt it.
 * Ledger insert and log line commit together, so a crash can't record one without the other.
 */
@Service
@ConditionalOnProperty(prefix = "edgedeploy", name = "mode", havingValue = "acknowledge")
public class DeploymentAcknowledger implements DeploymentRequestHandler {

    static final String CONSUMER = "worker-acknowledger";
    private static final Logger log = LoggerFactory.getLogger(DeploymentAcknowledger.class);

    private final DeploymentStore store;
    private final ProcessedEventLedger ledger;
    private final DeploymentLogService logs;
    private final DeploymentEventPublisher events;

    public DeploymentAcknowledger(DeploymentStore store, ProcessedEventLedger ledger, DeploymentLogService logs,
                                  DeploymentEventPublisher events) {
        this.store = store;
        this.ledger = ledger;
        this.logs = logs;
        this.events = events;
    }

    @Override
    @Transactional
    public void handle(DeploymentRequestedEvent event) {
        Optional<DeploymentStore.DeploymentSummary> deployment = store.findSummary(event.deploymentId());
        if (deployment.isEmpty()) {
            // The outbox only publishes after commit, so a missing row means it was deleted (project removed).
            log.warn("Deployment {} from event {} no longer exists; ignoring", event.deploymentId(), event.eventId());
            return;
        }
        if (!deployment.get().projectId().equals(event.projectId())) {
            throw new InvalidDeploymentEventException("Event " + event.eventId() + " names project " + event.projectId()
                    + " but deployment " + event.deploymentId() + " belongs to " + deployment.get().projectId());
        }
        if (!ledger.recordIfFirst(CONSUMER, event.eventId(), event.deploymentId())) {
            log.info("Duplicate event {} for deployment {}; already acknowledged", event.eventId(), event.deploymentId());
            return;
        }

        log.info("Deployment request received: deployment={} project={} repository={} branch={} commit={} status={}",
                event.deploymentId(), event.projectId(), event.repository(), event.branch(), event.commitSha(),
                deployment.get().status());
        // Same transaction as the ledger insert: either both are recorded or neither is.
        logs.append(event.deploymentId(), List.of(new DeploymentLogService.Entry(LogLevel.INFO, null,
                "Deployment request received by worker for " + event.repository() + "@"
                        + (event.commitSha() != null ? event.commitSha().substring(0, Math.min(7, event.commitSha().length())) : event.branch())
                        + ". Build pipeline disabled (WORKER_MODE=acknowledge); deployment stays "
                        + deployment.get().status() + ".")));
        // Notify only after commit, so a dashboard reacting to it is guaranteed to read the new log line.
        DeploymentStatus status = deployment.get().status();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.statusChanged(event.deploymentId(), event.projectId(), status, null, "Received by worker");
            }
        });
    }
}
