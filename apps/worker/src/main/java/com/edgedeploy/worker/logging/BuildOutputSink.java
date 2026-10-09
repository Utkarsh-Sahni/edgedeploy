package com.edgedeploy.worker.logging;

import com.edgedeploy.contracts.LogLevel;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Collects tool output (one {@code docker build} can print thousands of lines) and persists it in
 * batches, so log volume costs one INSERT round per batch rather than per line.
 *
 * <p>At most {@code maxLines} lines are stored. Beyond that, the most recent lines are kept in memory
 * and written on {@link #close()} after an "omitted" marker, because the end of a build log is
 * where the error usually is.
 */
public final class BuildOutputSink implements Consumer<String>, AutoCloseable {

    private static final int TAIL_AFTER_CAP = 100;

    private final UUID deploymentId;
    private final DeploymentLogService logs;
    private final int maxLines;
    private final int flushLines;
    private final long flushIntervalNanos;

    private final List<DeploymentLogService.Entry> buffer = new ArrayList<>();
    private final Deque<String> tailAfterCap = new ArrayDeque<>();
    private int persisted;
    private long omitted;
    private long lastFlush = System.nanoTime();
    private boolean closed;

    BuildOutputSink(UUID deploymentId, DeploymentLogService logs, int maxLines, int flushLines, Duration flushInterval) {
        this.deploymentId = deploymentId;
        this.logs = logs;
        this.maxLines = maxLines;
        this.flushLines = flushLines;
        this.flushIntervalNanos = flushInterval.toNanos();
    }

    @Override
    public synchronized void accept(String line) {
        if (closed || line == null || line.isBlank()) {
            return;
        }
        if (persisted + buffer.size() < maxLines) {
            buffer.add(new DeploymentLogService.Entry(LogLevel.INFO, null, line));
            if (buffer.size() >= flushLines || System.nanoTime() - lastFlush >= flushIntervalNanos) {
                flush();
            }
        } else {
            if (tailAfterCap.size() == TAIL_AFTER_CAP) {
                tailAfterCap.removeFirst();
                omitted++;
            }
            tailAfterCap.addLast(line);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (omitted > 0) {
            buffer.add(new DeploymentLogService.Entry(LogLevel.WARN, null,
                    "… " + omitted + " lines of output omitted; showing the last " + tailAfterCap.size() + " …"));
        }
        tailAfterCap.forEach(line -> buffer.add(new DeploymentLogService.Entry(LogLevel.INFO, null, line)));
        tailAfterCap.clear();
        flush();
        closed = true;
    }

    private void flush() {
        if (!buffer.isEmpty()) {
            logs.appendQuietly(deploymentId, List.copyOf(buffer));
            persisted += buffer.size();
            buffer.clear();
        }
        lastFlush = System.nanoTime();
    }
}
