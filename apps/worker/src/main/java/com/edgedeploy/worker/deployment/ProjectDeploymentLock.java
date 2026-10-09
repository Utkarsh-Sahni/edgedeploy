package com.edgedeploy.worker.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Serialises rollouts per project across all workers: only one deployment of a project may update its
 * ECS service at a time (builds and pushes still run in parallel).
 *
 * <p>Implemented as a Postgres session-level advisory lock on a dedicated connection. If the worker dies,
 * the connection drops and Postgres releases the lock: no TTLs to tune, no stale locks to clean up.
 * Each held lock costs one pooled connection for the duration of a rollout.
 */
@Component
public class ProjectDeploymentLock {

    /** Namespace for EdgeDeploy's advisory locks ('EDGE'), so they cannot clash with other users of the database. */
    private static final int LOCK_CLASS = 0x45444745;
    private static final Duration RETRY = Duration.ofSeconds(2);
    private static final Logger log = LoggerFactory.getLogger(ProjectDeploymentLock.class);

    private final DataSource dataSource;

    public ProjectDeploymentLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A held lock; closing it releases the lock and returns the connection. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Waits up to {@code timeout} for the project's lock.
     *
     * @param waiting told once if the lock is busy, so the deployment log can say why it is waiting
     * @return empty if it could not be acquired in time
     */
    public Optional<Handle> acquire(UUID projectId, Duration timeout, Consumer<String> waiting) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int key = projectId.hashCode();
        Connection connection;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not obtain a database connection for the deployment lock", e);
        }
        boolean announced = false;
        try {
            while (true) {
                if (tryLock(connection, key)) {
                    Connection held = connection;
                    connection = null;
                    return Optional.of(() -> release(held, key));
                }
                if (!announced) {
                    waiting.accept("Waiting for another deployment of this project to finish");
                    announced = true;
                }
                if (System.nanoTime() >= deadline) {
                    return Optional.empty();
                }
                Thread.sleep(RETRY);
            }
        } finally {
            if (connection != null) {
                closeQuietly(connection);
            }
        }
    }

    private static boolean tryLock(Connection connection, int key) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, LOCK_CLASS);
            statement.setInt(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not acquire the project deployment lock", e);
        }
    }

    private static void release(Connection connection, int key) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, LOCK_CLASS);
            statement.setInt(2, key);
            statement.execute();
        } catch (SQLException e) {
            log.warn("Could not release project deployment lock: {}", e.toString());
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing useful to do
        }
    }
}
