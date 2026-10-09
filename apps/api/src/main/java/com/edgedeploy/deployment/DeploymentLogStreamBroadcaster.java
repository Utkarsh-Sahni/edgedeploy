package com.edgedeploy.deployment;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.contracts.event.DeploymentLogEvent;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.repository.DeploymentLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Live deployment logs over Server-Sent Events.
 *
 * <p>Postgres is the source of truth; Kafka {@code deployment.logs} events only make delivery
 * immediate. Each subscriber gets, in order and without duplicates (by {@code seq}):
 * <ol>
 *   <li>the backlog after the client's last seen line ({@code Last-Event-ID} / {@code after}),</li>
 *   <li>live lines as they arrive (buffered while the backlog is being sent),</li>
 *   <li>a final catch-up from the database when the deployment settles, then the stream closes.</li>
 * </ol>
 * Each SSE event id is the line's {@code seq}, so a browser that reconnects resumes exactly where it stopped.
 */
@Component
public class DeploymentLogStreamBroadcaster {

    public static final String EVENT_NAME = "log";
    static final int MAX_BACKLOG = 5000;
    private static final Logger log = LoggerFactory.getLogger(DeploymentLogStreamBroadcaster.class);

    private final Map<UUID, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final DeploymentLogRepository logs;
    private final Supplier<SseEmitter> emitters;

    @Autowired
    public DeploymentLogStreamBroadcaster(DeploymentLogRepository logs, EdgeDeployProperties properties) {
        this(logs, () -> new SseEmitter(properties.sse().timeout().toMillis()));
    }

    /** Test seam: lets tests observe what is sent. */
    DeploymentLogStreamBroadcaster(DeploymentLogRepository logs, Supplier<SseEmitter> emitters) {
        this.logs = logs;
        this.emitters = emitters;
    }

    /**
     * @param afterSeq last line the client already has (0 for everything)
     * @param settled  the deployment has already finished: send what exists and close
     */
    public SseEmitter subscribe(UUID deploymentId, long afterSeq, boolean settled) {
        SseEmitter emitter = emitters.get();
        Subscriber subscriber = new Subscriber(deploymentId, emitter, afterSeq);
        if (!settled) {
            register(subscriber);
        }
        subscriber.deliverBacklog(fromDatabase(deploymentId, afterSeq));
        if (settled) {
            subscriber.finish(List.of());
        }
        return emitter;
    }

    /** Live lines from the worker (Kafka). */
    public void publish(DeploymentLogEvent event) {
        Set<Subscriber> set = subscribers.get(event.deploymentId());
        if (set != null) {
            set.forEach(subscriber -> subscriber.deliverLive(event.lines()));
        }
    }

    /** The deployment settled: flush anything not yet delivered from the database and close the streams. */
    public void settle(UUID deploymentId) {
        Set<Subscriber> set = subscribers.remove(deploymentId);
        if (set != null) {
            set.forEach(subscriber -> subscriber.finish(fromDatabase(deploymentId, subscriber.lastSeq())));
        }
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        subscribers.values().forEach(set -> set.forEach(Subscriber::keepAlive));
    }

    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }

    private List<DeploymentLogEvent.Line> fromDatabase(UUID deploymentId, long afterSeq) {
        return logs.findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(deploymentId, afterSeq, Limit.of(MAX_BACKLOG)).stream()
                .map(DeploymentLogStreamBroadcaster::toLine)
                .toList();
    }

    static DeploymentLogEvent.Line toLine(DeploymentLog entity) {
        return new DeploymentLogEvent.Line(entity.getSeq(), entity.getLevel(), entity.getStep(), entity.getMessage(),
                entity.getTimestamp());
    }

    private void register(Subscriber subscriber) {
        subscribers.compute(subscriber.deploymentId, (id, set) -> {
            Set<Subscriber> result = set != null ? set : ConcurrentHashMap.newKeySet();
            result.add(subscriber);
            return result;
        });
        Runnable unregister = () -> unregister(subscriber);
        subscriber.emitter.onCompletion(unregister);
        subscriber.emitter.onTimeout(unregister);
        subscriber.emitter.onError(error -> unregister.run());
    }

    private void unregister(Subscriber subscriber) {
        subscribers.computeIfPresent(subscriber.deploymentId, (id, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * One SSE connection. All methods are synchronized so backlog, live and final lines are written to
     * the stream in {@code seq} order from whichever thread delivers them.
     */
    private final class Subscriber {
        private final UUID deploymentId;
        private final SseEmitter emitter;
        private final List<DeploymentLogEvent.Line> pending = new ArrayList<>();
        private long lastSeq;
        private boolean backlogSent;
        private boolean closed;

        Subscriber(UUID deploymentId, SseEmitter emitter, long afterSeq) {
            this.deploymentId = deploymentId;
            this.emitter = emitter;
            this.lastSeq = afterSeq;
        }

        synchronized long lastSeq() {
            return lastSeq;
        }

        synchronized void deliverBacklog(List<DeploymentLogEvent.Line> backlog) {
            send(backlog);
            backlogSent = true;
            pending.sort(Comparator.comparingLong(DeploymentLogEvent.Line::seq));
            send(pending);
            pending.clear();
        }

        synchronized void deliverLive(List<DeploymentLogEvent.Line> lines) {
            if (!backlogSent) {
                pending.addAll(lines);
            } else {
                send(lines);
            }
        }

        synchronized void finish(List<DeploymentLogEvent.Line> remaining) {
            send(remaining);
            if (!closed) {
                closed = true;
                emitter.complete();
            }
        }

        synchronized void keepAlive() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                close();
            }
        }

        private void send(List<DeploymentLogEvent.Line> lines) {
            for (DeploymentLogEvent.Line line : lines) {
                if (closed || line.seq() <= lastSeq) {
                    continue;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .name(EVENT_NAME)
                            .id(Long.toString(line.seq()))
                            .data(line, MediaType.APPLICATION_JSON));
                    lastSeq = line.seq();
                } catch (IOException | IllegalStateException e) {
                    log.debug("Dropping log subscriber for deployment {}: {}", deploymentId, e.toString());
                    close();
                }
            }
        }

        private void close() {
            closed = true;
            unregister(this);
        }
    }
}
