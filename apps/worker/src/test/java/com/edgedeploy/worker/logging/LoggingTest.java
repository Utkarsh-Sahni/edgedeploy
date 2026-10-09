package com.edgedeploy.worker.logging;

import com.edgedeploy.contracts.LogLevel;
import com.edgedeploy.worker.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoggingTest {

    private final SecretRedactor redactor = new SecretRedactor(TestProperties.create(Path.of("/tmp"), "https://github.com"));

    @Test
    void redactsConfiguredTokenInEveryFormGitCouldPrintIt() {
        String basic = Base64.getEncoder().encodeToString(("x-access-token:" + TestProperties.DEPLOY_TOKEN).getBytes());

        assertThat(redactor.redact("token=" + TestProperties.DEPLOY_TOKEN)).isEqualTo("token=[REDACTED]");
        assertThat(redactor.redact("Authorization: Basic " + basic)).doesNotContain(basic);
        assertThat(redactor.redact("fatal: https://bot:hunter2@github.com/o/r.git")).isEqualTo("fatal: https://[REDACTED]@github.com/o/r.git");
        assertThat(redactor.redact("leaked gho_abcdefghijklmnopqrstuvwxyz123456 in output")).doesNotContain("gho_");
        assertThat(redactor.redact("github_pat_11ABCDEFG0123456789_abcdefghijklmnop")).isEqualTo("[REDACTED]");
        assertThat(redactor.redact("npm ERR! code E404")).isEqualTo("npm ERR! code E404");
    }

    @Test
    void outputSinkBatchesAndKeepsTheTailOnceTheCapIsReached() {
        DeploymentLogService logs = mock(DeploymentLogService.class);
        UUID id = UUID.randomUUID();
        BuildOutputSink sink = new BuildOutputSink(id, logs, 10, 4, Duration.ofHours(1));

        for (int i = 1; i <= 250; i++) {
            sink.accept("line " + i);
        }
        sink.accept("   "); // blank lines are dropped
        sink.close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeploymentLogService.Entry>> batches = ArgumentCaptor.forClass(List.class);
        verify(logs, atLeastOnce()).appendQuietly(eq(id), batches.capture());
        List<String> persisted = new ArrayList<>();
        batches.getAllValues().forEach(batch -> batch.forEach(e -> persisted.add(e.message())));

        assertThat(persisted.subList(0, 10)).containsExactly("line 1", "line 2", "line 3", "line 4", "line 5",
                "line 6", "line 7", "line 8", "line 9", "line 10");
        assertThat(persisted).contains("… 140 lines of output omitted; showing the last 100 …");
        assertThat(persisted.getLast()).isEqualTo("line 250");
        assertThat(persisted).hasSize(10 + 1 + 100);
        assertThat(batches.getAllValues().getFirst()).hasSize(4); // flushed in batches, not per line
        assertThat(batches.getAllValues().stream().flatMap(List::stream)
                .filter(e -> e.level() == LogLevel.WARN)).hasSize(1);
    }
}
