package com.edgedeploy.deployment;

import com.edgedeploy.contracts.DeploymentStep;
import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.contracts.event.DeploymentLogEvent;
import com.edgedeploy.entity.DeploymentLog;
import com.edgedeploy.repository.DeploymentLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentLogStreamBroadcasterTest {

    private static final UUID DEPLOYMENT = UUID.randomUUID();

    private final DeploymentLogRepository repository = mock(DeploymentLogRepository.class);
    private final List<RecordingEmitter> emitters = new ArrayList<>();
    private final DeploymentLogStreamBroadcaster broadcaster = new DeploymentLogStreamBroadcaster(repository, () -> {
        RecordingEmitter emitter = new RecordingEmitter();
        emitters.add(emitter);
        return emitter;
    });

    @Test
    void sendsBacklogThenLiveLinesInOrderWithoutDuplicates() {
        when(repository.findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(eq(DEPLOYMENT), eq(0L), any(Limit.class)))
                .thenReturn(List.of(entity(1, DeploymentStep.QUEUED), entity(2, null)));

        broadcaster.subscribe(DEPLOYMENT, 0, false);
        broadcaster.publish(new DeploymentLogEvent(DEPLOYMENT, List.of(line(2), line(3))));
        broadcaster.publish(new DeploymentLogEvent(DEPLOYMENT, List.of(line(4))));
        broadcaster.publish(new DeploymentLogEvent(UUID.randomUUID(), List.of(line(99)))); // other deployment

        assertThat(emitters.getFirst().ids).containsExactly(1L, 2L, 3L, 4L);
        assertThat(emitters.getFirst().completed).isFalse();
    }

    @Test
    void resumesAfterTheClientsLastSeenLine() {
        broadcaster.subscribe(DEPLOYMENT, 41, false);

        verify(repository).findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(eq(DEPLOYMENT), eq(41L), any(Limit.class));
    }

    @Test
    void settlingFlushesRemainingLinesFromTheDatabaseAndCloses() {
        when(repository.findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(eq(DEPLOYMENT), anyLong(), any(Limit.class)))
                .thenReturn(List.of(entity(1, DeploymentStep.QUEUED)))
                .thenReturn(List.of(entity(2, null), entity(3, DeploymentStep.IMAGE)));

        broadcaster.subscribe(DEPLOYMENT, 0, false);
        broadcaster.settle(DEPLOYMENT);

        RecordingEmitter emitter = emitters.getFirst();
        assertThat(emitter.ids).containsExactly(1L, 2L, 3L);
        assertThat(emitter.completed).isTrue();
        verify(repository).findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(eq(DEPLOYMENT), eq(1L), any(Limit.class));
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    @Test
    void alreadySettledDeploymentGetsItsLogsAndAClosedStream() {
        when(repository.findByDeploymentIdAndSeqGreaterThanOrderBySeqAsc(eq(DEPLOYMENT), eq(0L), any(Limit.class)))
                .thenReturn(List.of(entity(1, DeploymentStep.QUEUED), entity(2, DeploymentStep.IMAGE)));

        broadcaster.subscribe(DEPLOYMENT, 0, true);

        assertThat(emitters.getFirst().ids).containsExactly(1L, 2L);
        assertThat(emitters.getFirst().completed).isTrue();
        assertThat(broadcaster.subscriberCount()).isZero();
    }

    private static DeploymentLog entity(long seq, DeploymentStep step) {
        DeploymentLog log = DeploymentLog.milestone(DEPLOYMENT, step, "line " + seq, Instant.now());
        ReflectionTestUtils.setField(log, "seq", seq);
        return log;
    }

    private static DeploymentLogEvent.Line line(long seq) {
        return new DeploymentLogEvent.Line(seq, LogLevel.INFO, null, "line " + seq, Instant.now());
    }

    /** Captures SSE event ids (= log seq) instead of writing to a response. */
    private static final class RecordingEmitter extends SseEmitter {
        private static final Pattern ID = Pattern.compile("(?m)^id:(\\d+)$");
        final List<Long> ids = new ArrayList<>();
        boolean completed;

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                if (part.getMediaType() == null || !part.getMediaType().equals(MediaType.APPLICATION_JSON)) {
                    text.append(part.getData());
                }
            }
            Matcher matcher = ID.matcher(text);
            while (matcher.find()) {
                ids.add(Long.parseLong(matcher.group(1)));
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
