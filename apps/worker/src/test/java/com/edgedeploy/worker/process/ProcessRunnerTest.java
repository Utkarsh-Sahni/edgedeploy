package com.edgedeploy.worker.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessRunnerTest {

    private static final Map<String, String> ENV = Map.of("PATH", "/usr/bin:/bin");

    @TempDir
    Path dir;

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void streamsOutputAndReturnsExitCode() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        ProcessResult result = runner.run(spec(List.of("sh", "-c", "echo one; echo two >&2; exit 3"), Duration.ofSeconds(10), lines));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(lines).containsExactly("one", "two");
        assertThat(result.tail()).containsExactly("one", "two");
    }

    @Test
    void childSeesOnlyTheExplicitEnvironment() throws Exception {
        ProcessResult result = runner.run(new ProcessSpec(List.of("sh", "-c", "echo \"[$HOME][$GITHUB_DEPLOY_TOKEN][$ONLY]\""),
                dir, Map.of("PATH", "/usr/bin:/bin", "ONLY", "set"), Duration.ofSeconds(10), l -> { }, () -> false));

        assertThat(result.tail()).containsExactly("[][][set]");
    }

    @Test
    void argumentsAreNotInterpretedByAShell() throws Exception {
        ProcessResult result = runner.run(spec(List.of("echo", "$(touch pwned); rm -rf /"), Duration.ofSeconds(10), null));

        assertThat(result.tail()).containsExactly("$(touch pwned); rm -rf /");
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    @Test
    void killsProcessesThatExceedTheTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(spec(List.of("sleep", "30"), Duration.ofMillis(500), null)))
                .isInstanceOfSatisfying(ProcessException.class, e -> assertThat(e.reason()).isEqualTo(ProcessException.Reason.TIMED_OUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void stopsWhenCancelled() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertThatThrownBy(() -> runner.run(new ProcessSpec(List.of("sleep", "30"), dir, ENV, Duration.ofSeconds(30),
                l -> { }, cancelled::get)))
                .isInstanceOfSatisfying(ProcessException.class, e -> assertThat(e.reason()).isEqualTo(ProcessException.Reason.CANCELLED));
    }

    @Test
    void missingExecutableIsAStartFailure() {
        assertThatThrownBy(() -> runner.run(spec(List.of("definitely-not-a-real-binary-xyz"), Duration.ofSeconds(5), null)))
                .isInstanceOfSatisfying(ProcessException.class, e -> assertThat(e.reason()).isEqualTo(ProcessException.Reason.START_FAILED));
    }

    private ProcessSpec spec(List<String> command, Duration timeout, List<String> sink) {
        return new ProcessSpec(command, dir, ENV, timeout, sink != null ? sink::add : l -> { }, () -> false);
    }
}
