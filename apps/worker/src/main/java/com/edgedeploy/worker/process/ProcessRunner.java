package com.edgedeploy.worker.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs external tools (git, docker) under strict control: argument vectors only, an explicit
 * environment, a hard timeout, cooperative cancellation and bounded memory for captured output.
 * When a process is stopped, its whole process tree is killed.
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final int TAIL_LINES = 40;
    private static final int MAX_LINE_LENGTH = 4000;
    private static final Duration POLL = Duration.ofMillis(250);
    private static final Duration CANCELLATION_CHECK_INTERVAL = Duration.ofSeconds(3);

    public ProcessResult run(ProcessSpec spec) throws ProcessException {
        ProcessBuilder builder = new ProcessBuilder(spec.command())
                .directory(spec.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(spec.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessException(ProcessException.Reason.START_FAILED,
                    "Could not start '" + spec.command().getFirst() + "': " + e.getMessage(), e);
        }
        try {
            process.getOutputStream().close(); // no stdin: tools must never wait for input
        } catch (IOException ignored) {
            // the child may already have exited
        }

        Deque<String> tail = new ArrayDeque<>(TAIL_LINES);
        Thread reader = Thread.ofVirtual().name("process-output-" + process.pid()).start(() -> pump(process, spec, tail));

        long deadline = System.nanoTime() + spec.timeout().toNanos();
        long nextCancellationCheck = System.nanoTime();
        try {
            while (!process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                long now = System.nanoTime();
                if (now >= deadline) {
                    kill(process);
                    throw new ProcessException(ProcessException.Reason.TIMED_OUT,
                            "Timed out after " + spec.timeout().toSeconds() + "s", null);
                }
                if (now >= nextCancellationCheck) {
                    nextCancellationCheck = now + CANCELLATION_CHECK_INTERVAL.toNanos();
                    if (spec.cancelled().getAsBoolean()) {
                        kill(process);
                        throw new ProcessException(ProcessException.Reason.CANCELLED, "Cancelled", null);
                    }
                }
            }
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ProcessException(ProcessException.Reason.CANCELLED, "Interrupted", e);
        } finally {
            joinQuietly(reader);
        }

        synchronized (tail) {
            return new ProcessResult(process.exitValue(), List.copyOf(new ArrayList<>(tail)));
        }
    }

    private static void pump(Process process, ProcessSpec spec, Deque<String> tail) {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                String bounded = line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "…" : line;
                synchronized (tail) {
                    if (tail.size() == TAIL_LINES) {
                        tail.removeFirst();
                    }
                    tail.addLast(bounded);
                }
                try {
                    spec.onOutput().accept(bounded);
                } catch (RuntimeException e) {
                    log.warn("Output listener failed: {}", e.toString());
                }
            }
        } catch (IOException e) {
            // Stream closes when the process is killed; nothing to do.
        }
    }

    /** Kill the whole tree: docker/git spawn helpers that would otherwise outlive the parent. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread reader) {
        try {
            reader.join(Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
