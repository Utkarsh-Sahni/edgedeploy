package com.edgedeploy.worker.deployment;

import com.edgedeploy.contracts.DeploymentStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The worker's only write path to {@code deployments}.
 *
 * <p>Every transition is a single compare-and-set statement ({@code ... WHERE status = :from}).
 * That makes duplicate Kafka deliveries harmless (only one consumer can claim a QUEUED row) and
 * stops a stale worker from overwriting a newer state, e.g. after a user cancelled the deployment.
 * No distributed lock is needed: Postgres row-level atomicity is the lock. Callers go through
 * {@code DeploymentStatusService}, which validates transitions and logs them.
 */
@Repository
public class DeploymentStore {

    private static final int MAX_ERROR_LENGTH = 2000;

    public record DeploymentSummary(UUID projectId, DeploymentStatus status) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public DeploymentStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Atomically moves QUEUED -> BUILDING and returns what the pipeline needs.
     *
     * @return empty if the deployment does not exist or was already claimed (duplicate event)
     */
    public Optional<ClaimedDeployment> claim(UUID deploymentId) {
        return jdbc.sql("""
                        UPDATE deployments d
                           SET status = :to, started_at = :now, updated_at = :now
                          FROM projects p
                         WHERE d.id = :id AND d.status = :from AND p.id = d.project_id
                     RETURNING d.id, d.project_id, d.number, p.repository, p.branch, d.commit_sha,
                               p.framework, p.default_build_command, p.default_start_command
                        """)
                .param("id", deploymentId)
                .param("from", DeploymentStatus.QUEUED.name())
                .param("to", DeploymentStatus.BUILDING.name())
                .param("now", now())
                .query((rs, row) -> new ClaimedDeployment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getInt("number"),
                        rs.getString("repository"),
                        rs.getString("branch"),
                        rs.getString("commit_sha"),
                        rs.getString("framework"),
                        rs.getString("default_build_command"),
                        rs.getString("default_start_command")))
                .optional();
    }

    /** @return false if the row was no longer in {@code from} (someone else moved it) */
    public boolean transition(UUID deploymentId, DeploymentStatus from, DeploymentStatus to) {
        requireAllowed(from, to);
        return jdbc.sql("UPDATE deployments SET status = :to, updated_at = :now WHERE id = :id AND status = :from")
                .param("id", deploymentId)
                .param("from", from.name())
                .param("to", to.name())
                .param("now", now())
                .update() == 1;
    }

    /**
     * BUILDING -> IMAGE_BUILT with the image reference. While no deployment target exists (Phase 3) this is
     * where the pipeline ends, so the completion time is recorded too.
     */
    public boolean markImageBuilt(UUID deploymentId, String imageUri, boolean pipelineComplete) {
        requireAllowed(DeploymentStatus.BUILDING, DeploymentStatus.IMAGE_BUILT);
        OffsetDateTime now = now();
        return jdbc.sql("""
                        UPDATE deployments
                           SET status = :to, image_uri = :image, updated_at = :now,
                               completed_at = CASE WHEN :complete THEN :now ELSE completed_at END
                         WHERE id = :id AND status = :from
                        """)
                .param("id", deploymentId)
                .param("from", DeploymentStatus.BUILDING.name())
                .param("to", DeploymentStatus.IMAGE_BUILT.name())
                .param("image", imageUri)
                .param("complete", pipelineComplete)
                .param("now", now)
                .update() == 1;
    }

    public boolean markFailed(UUID deploymentId, DeploymentStatus from, String errorMessage) {
        requireAllowed(from, DeploymentStatus.FAILED);
        return jdbc.sql("""
                        UPDATE deployments
                           SET status = :to, error_message = :error, completed_at = :now, updated_at = :now
                         WHERE id = :id AND status = :from
                        """)
                .param("id", deploymentId)
                .param("from", from.name())
                .param("to", DeploymentStatus.FAILED.name())
                .param("error", truncate(errorMessage))
                .param("now", now())
                .update() == 1;
    }

    /** Records the SHA a branch-tip deployment resolved to, so the deployment stays reproducible. */
    public void recordCommit(UUID deploymentId, String commitSha) {
        jdbc.sql("UPDATE deployments SET commit_sha = :sha, updated_at = :now WHERE id = :id AND commit_sha IS NULL")
                .param("id", deploymentId)
                .param("sha", commitSha)
                .param("now", now())
                .update();
    }

    /** Stores where the pushed image lives: the tag for humans, the digest for exact rollbacks. */
    public void recordImage(UUID deploymentId, String imageUri, String imageDigest) {
        jdbc.sql("UPDATE deployments SET image_uri = :uri, image_digest = :digest, updated_at = :now WHERE id = :id")
                .param("id", deploymentId)
                .param("uri", imageUri)
                .param("digest", imageDigest)
                .param("now", now())
                .update();
    }

    /** Stores what the deployment runs on (cluster, service, task definition revision), for rollback later. */
    public void recordRollout(UUID deploymentId, String cluster, String service, String taskDefinitionArn) {
        jdbc.sql("""
                        UPDATE deployments SET ecs_cluster = :cluster, ecs_service = :service,
                               ecs_task_definition_arn = :taskDefinition, updated_at = :now
                         WHERE id = :id
                        """)
                .param("id", deploymentId)
                .param("cluster", cluster)
                .param("service", service)
                .param("taskDefinition", taskDefinitionArn)
                .param("now", now())
                .update();
    }

    /** HEALTH_CHECK -> RUNNING with the public URL. */
    public boolean markRunning(UUID deploymentId, String deploymentUrl, String taskArn) {
        requireAllowed(DeploymentStatus.HEALTH_CHECK, DeploymentStatus.RUNNING);
        OffsetDateTime now = now();
        return jdbc.sql("""
                        UPDATE deployments
                           SET status = :to, deployment_url = :url, ecs_task_arn = :task, completed_at = :now, updated_at = :now
                         WHERE id = :id AND status = :from
                        """)
                .param("id", deploymentId)
                .param("from", DeploymentStatus.HEALTH_CHECK.name())
                .param("to", DeploymentStatus.RUNNING.name())
                .param("url", deploymentUrl)
                .param("task", taskArn)
                .param("now", now)
                .update() == 1;
    }

    /** One service per project: the deployment that was serving before is no longer running. */
    public List<NumberedDeployment> stopOtherRunning(UUID projectId, UUID except) {
        return jdbc.sql("""
                        UPDATE deployments SET status = :stopped, updated_at = :now
                         WHERE project_id = :project AND status = :running AND id <> :except
                     RETURNING id, number
                        """)
                .param("stopped", DeploymentStatus.STOPPED.name())
                .param("running", DeploymentStatus.RUNNING.name())
                .param("project", projectId)
                .param("except", except)
                .param("now", now())
                .query((rs, row) -> new NumberedDeployment(rs.getObject("id", UUID.class), rs.getInt("number")))
                .list();
    }

    public record NumberedDeployment(UUID deploymentId, int number) {
    }

    /** -> STOPPED (cancelled or superseded); compare-and-set like every other transition. */
    public boolean markStopped(UUID deploymentId, DeploymentStatus from) {
        requireAllowed(from, DeploymentStatus.STOPPED);
        return jdbc.sql("""
                        UPDATE deployments SET status = :to, completed_at = :now, updated_at = :now
                         WHERE id = :id AND status = :from
                        """)
                .param("id", deploymentId)
                .param("from", from.name())
                .param("to", DeploymentStatus.STOPPED.name())
                .param("now", now())
                .update() == 1;
    }

    /** The task definition the project is serving right now (for automatic rollback), if any. */
    public Optional<String> runningTaskDefinition(UUID projectId, UUID except) {
        return jdbc.sql("""
                        SELECT ecs_task_definition_arn FROM deployments
                         WHERE project_id = :project AND status = :running AND id <> :except
                           AND ecs_task_definition_arn IS NOT NULL
                         ORDER BY number DESC LIMIT 1
                        """)
                .param("project", projectId)
                .param("running", DeploymentStatus.RUNNING.name())
                .param("except", except)
                .query(String.class)
                .optional();
    }

    /** A live deployment running on ECS (budget mode keeps its URL in step with the task actually serving it). */
    public record LiveDeployment(UUID deploymentId, UUID projectId, String cluster, String service, String taskDefinitionArn,
                                 String taskArn, String url) {
    }

    public List<LiveDeployment> runningOnEcs() {
        return jdbc.sql("""
                        SELECT id, project_id, ecs_cluster, ecs_service, ecs_task_definition_arn, ecs_task_arn, deployment_url
                          FROM deployments
                         WHERE status = :running AND ecs_service IS NOT NULL AND ecs_task_definition_arn IS NOT NULL
                        """)
                .param("running", DeploymentStatus.RUNNING.name())
                .query((rs, row) -> new LiveDeployment(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getString("ecs_cluster"), rs.getString("ecs_service"), rs.getString("ecs_task_definition_arn"),
                        rs.getString("ecs_task_arn"), rs.getString("deployment_url")))
                .list();
    }

    /** Compare-and-set on the task: a concurrent update (another worker) wins and this one does nothing. */
    public boolean updateLiveAddress(UUID deploymentId, String expectedTaskArn, String taskArn, String url) {
        return jdbc.sql("""
                        UPDATE deployments SET ecs_task_arn = :task, deployment_url = :url, updated_at = :now
                         WHERE id = :id AND status = :running AND ecs_task_arn IS NOT DISTINCT FROM CAST(:expected AS VARCHAR)
                        """)
                .param("id", deploymentId)
                .param("running", DeploymentStatus.RUNNING.name())
                .param("expected", expectedTaskArn)
                .param("task", taskArn)
                .param("url", url)
                .param("now", now())
                .update() == 1;
    }

    /**
     * The highest-numbered newer deployment of the project that has already started rolling out or is
     * live. If one exists, rolling this (older) deployment out would replace newer code with older code.
     */
    public Optional<Integer> newerDeploymentStarted(UUID projectId, int number) {
        return jdbc.sql("""
                        SELECT max(number) FROM deployments
                         WHERE project_id = :project AND number > :number AND status IN ('DEPLOYING', 'HEALTH_CHECK', 'RUNNING')
                        """)
                .param("project", projectId)
                .param("number", number)
                .query(Integer.class)
                .optional();
    }

    /**
     * Fails deployments stuck in an in-progress status since before {@code cutoff}: their worker died (a live
     * worker always records an outcome within the deployment timeout).
     */
    public List<DeploymentSummaryWithId> failStuckDeployments(Instant cutoff, String errorMessage) {
        return jdbc.sql("""
                        UPDATE deployments
                           SET status = :failed, error_message = :error, completed_at = :now, updated_at = :now
                         WHERE status IN ('BUILDING', 'PUSHING', 'DEPLOYING', 'HEALTH_CHECK') AND updated_at < :cutoff
                     RETURNING id, project_id
                        """)
                .param("failed", DeploymentStatus.FAILED.name())
                .param("error", errorMessage)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("now", now())
                .query((rs, row) -> new DeploymentSummaryWithId(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class)))
                .list();
    }

    public record DeploymentSummaryWithId(UUID deploymentId, UUID projectId) {
    }

    public Optional<DeploymentSummary> findSummary(UUID deploymentId) {
        return jdbc.sql("SELECT project_id, status FROM deployments WHERE id = :id")
                .param("id", deploymentId)
                .query((rs, row) -> new DeploymentSummary(
                        rs.getObject("project_id", UUID.class), DeploymentStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    public Optional<DeploymentStatus> currentStatus(UUID deploymentId) {
        return jdbc.sql("SELECT status FROM deployments WHERE id = :id")
                .param("id", deploymentId)
                .query(String.class)
                .optional()
                .map(DeploymentStatus::valueOf);
    }

    private static void requireAllowed(DeploymentStatus from, DeploymentStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("Illegal deployment transition " + from + " -> " + to);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
