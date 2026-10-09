package com.edgedeploy.worker.process;

/** The process could not run to completion: not startable, timed out or cancelled. */
public class ProcessException extends Exception {

    public enum Reason {
        /** Executable missing or not runnable. */
        START_FAILED,
        TIMED_OUT,
        CANCELLED
    }

    private final Reason reason;

    public ProcessException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
