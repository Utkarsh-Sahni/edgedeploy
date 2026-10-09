package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import com.edgedeploy.contracts.event.DeploymentRequestedEvent;
import com.edgedeploy.worker.config.WorkerProperties;
import com.edgedeploy.worker.kafka.InvalidDeploymentEventException;
import com.edgedeploy.worker.logging.DeploymentLogService;
import com.edgedeploy.worker.service.DeploymentStatusService;
import com.edgedeploy.worker.workspace.Workspace;
import com.edgedeploy.worker.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one deployment request end to end and guarantees it ends in a recorded outcome.
 *
 * <ol>
 *   <li><b>Idempotency</b>: the deployment id is the key. Only a QUEUED deployment can be claimed
 *       (QUEUED -> BUILDING compare-and-set), so duplicate or late Kafka deliveries are skipped.</li>
 *   <li><b>Isolation</b>: a fresh workspace per deployment, always deleted afterwards.</li>
 *   <li><b>Outcome</b>: every failure becomes FAILED with a user-facing reason; a cancel stops work; a
 *       bug in one deployment never kills the consumer, so later messages are still processed.</li>
 * </ol>
 * Only infrastructure failures before the claim (e.g. database down) propagate, so the Kafka error
 * handler can retry them.
 */
@Service
@ConditionalOnProperty(prefix = "edgedeploy", name = "mode", havingValue = "build", matchIfMissing = true)
public class DeploymentProcessor implements DeploymentRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(DeploymentProcessor.class);

    private final DeploymentStore store;
    private final DeploymentStatusService status;
    private final DeploymentPipeline pipeline;
    private final WorkspaceManager workspaces;
    private final DeploymentLogService logs;
    private final CancellationProbe cancellation;
    private final WorkerProperties properties;
    private final Clock clock;
    private final String workerName;

    public DeploymentProcessor(DeploymentStore store, DeploymentStatusService status, DeploymentPipeline pipeline,
                               WorkspaceManager workspaces, DeploymentLogService logs, CancellationProbe cancellation,
                               WorkerProperties properties, Clock clock) {
        this.store = store;
        this.status = status;
        this.pipeline = pipeline;
        this.workspaces = workspaces;
        this.logs = logs;
        this.cancellation = cancellation;
        this.properties = properties;
        this.clock = clock;
        this.workerName = hostname();
    }

    @Override
    public void handle(DeploymentRequestedEvent event) {
        UUID id = event.deploymentId();
        Optional<DeploymentStore.DeploymentSummary> summary = store.findSummary(id);
        if (summary.isEmpty()) {
            // The outbox publishes only after commit, so a missing row was deleted (its project was removed).
            log.warn("Deployment {} no longer exists; ignoring event {}", id, event.eventId());
            return;
        }
        if (!summary.get().projectId().equals(event.projectId())) {
            throw new InvalidDeploymentEventException("Event " + event.eventId() + " names project " + event.projectId()
                    + " but deployment " + id + " belongs to " + summary.get().projectId());
        }
        if (summary.get().status() != DeploymentStatus.QUEUED) {
            log.info("Skipping event {}: deployment {} is already {} (duplicate or late delivery)",
                    event.eventId(), id, summary.get().status());
            return;
        }
        Optional<ClaimedDeployment> claimed = status.claim(id, workerName);
        if (claimed.isEmpty()) {
            log.info("Deployment {} was claimed concurrently; skipping event {}", id, event.eventId());
            return;
        }
        run(claimed.get());
    }

    private void run(ClaimedDeployment deployment) {
        UUID id = deployment.deploymentId();
        log.info("Building deployment #{} {} ({}@{} {})", deployment.number(), id, deployment.repository(),
                deployment.branch(), deployment.commitSha());
        Workspace workspace = null;
        DeploymentContext context = null;
        try {
            workspace = workspaces.create(id);
            context = new DeploymentContext(deployment, workspace, properties.timeouts().deployment(),
                    cancellation.forDeployment(id), clock);
            pipeline.run(context);
            log.info("Deployment {} finished as {}", id, context.status());
        } catch (DeploymentFailure failure) {
            log.info("Deployment {} failed during {}: {}", id, failure.step(), failure.getMessage());
            status.fail(id, deployment.projectId(), context.status(), failure.step(), failure.getMessage());
        } catch (DeploymentCancelledException cancelled) {
            log.info("Deployment {} was cancelled; build stopped", id);
            logs.warn(id, "Deployment cancelled: build stopped and workspace removed");
        } catch (DeploymentStatusService.ConcurrentStatusChangeException e) {
            log.warn("Abandoning deployment {}: {}", id, e.getMessage());
        } catch (IOException e) {
            log.error("Could not prepare workspace for deployment {}", id, e);
            status.fail(id, deployment.projectId(), DeploymentStatus.BUILDING, null,
                    "The build worker could not prepare a workspace. Try deploying again.");
        } catch (RuntimeException e) {
            // A bug or unexpected infrastructure error. Full details stay in the worker log; users get a
            // generic message because exception text can contain paths, hosts or credentials.
            log.error("Unexpected error in deployment {}", id, e);
            status.fail(id, deployment.projectId(), context != null ? context.status() : DeploymentStatus.BUILDING,
                    context != null ? context.step() : null, "Internal error in the build worker. Try deploying again.");
        } finally {
            if (workspace != null) {
                workspaces.delete(workspace);
            }
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "worker";
        }
    }
}
