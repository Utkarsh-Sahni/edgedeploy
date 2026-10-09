package com.edgedeploy.deployment;

import com.edgedeploy.config.EdgeDeployProperties;
import com.edgedeploy.contracts.event.DeploymentStatusEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of Server-Sent-Event subscribers, keyed by deployment id.
 *
 * <p>Events are change notifications: clients re-read the deployment over REST on each event,
 * so ordering glitches between the snapshot and live events can never leave the UI stale.
 */
@Component
public class DeploymentEventBroadcaster {

    public static final String EVENT_NAME = "deployment";
    private static final Logger log = LoggerFactory.getLogger(DeploymentEventBroadcaster.class);

    private final Map<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Duration timeout;

    public DeploymentEventBroadcaster(EdgeDeployProperties properties) {
        this.timeout = properties.sse().timeout();
    }

    /** Register before reading the snapshot, so no event can fall between the two. */
    public SseEmitter subscribe(UUID deploymentId) {
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        subscribers.compute(deploymentId, (id, set) -> {
            Set<SseEmitter> emitters = set != null ? set : ConcurrentHashMap.newKeySet();
            emitters.add(emitter);
            return emitters;
        });
        Runnable unsubscribe = () -> remove(deploymentId, emitter);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(error -> unsubscribe.run());
        return emitter;
    }

    /** Sends to a single subscriber (initial snapshot). */
    public void send(SseEmitter emitter, DeploymentStatusEvent event) {
        deliver(emitter, event);
    }

    /** Fans an event out to every subscriber of that deployment on this instance. */
    public void publish(DeploymentStatusEvent event) {
        Set<SseEmitter> emitters = subscribers.get(event.deploymentId());
        if (emitters != null) {
            emitters.forEach(emitter -> deliver(emitter, event));
        }
    }

    /** Settled states (RUNNING/FAILED/STOPPED) end the stream; a failed send unsubscribes the client. */
    private void deliver(SseEmitter emitter, DeploymentStatusEvent event) {
        if (!trySend(emitter, event)) {
            remove(event.deploymentId(), emitter);
        } else if (event.status().isSettled()) {
            emitter.complete();
        }
    }

    /** Keeps idle connections open through proxies/load balancers that cut silent streams. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        subscribers.forEach((deploymentId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                remove(deploymentId, emitter);
            }
        }));
    }

    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }

    private boolean trySend(SseEmitter emitter, DeploymentStatusEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(EVENT_NAME)
                    .id(event.eventId().toString())
                    .data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            // Client went away or the emitter already completed; not an error worth more than debug.
            log.debug("Dropping SSE subscriber for deployment {}: {}", event.deploymentId(), e.toString());
            return false;
        }
    }

    private void remove(UUID deploymentId, SseEmitter emitter) {
        subscribers.computeIfPresent(deploymentId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
