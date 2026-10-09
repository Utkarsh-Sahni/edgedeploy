package com.edgedeploy.worker.process;

import java.util.List;

/**
 * @param tail the last lines of merged output, for building error messages
 */
public record ProcessResult(int exitCode, List<String> tail) {

    public boolean succeeded() {
        return exitCode == 0;
    }

    public String output() {
        return String.join("\n", tail);
    }
}
