package com.edgedeploy.worker.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A command to run. The command is an argument vector executed directly (no shell), so arguments are
 * never re-parsed: a branch name or path containing spaces, quotes or ';' is just data.
 *
 * @param environment the <em>complete</em> environment of the child process; nothing is inherited from
 *                    the worker, so secrets in the worker's environment cannot leak into it
 * @param onOutput    receives each merged stdout/stderr line as it is produced (may be a no-op)
 * @param cancelled   polled while the process runs; returning true kills it
 */
public record ProcessSpec(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        Consumer<String> onOutput,
        BooleanSupplier cancelled) {

    public ProcessSpec {
        if (command.isEmpty() || command.stream().anyMatch(arg -> arg == null || arg.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Invalid command");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
    }

    /** For logs and error messages. Arguments are never secret by construction (secrets go via environment). */
    public String describe() {
        return String.join(" ", command);
    }
}
