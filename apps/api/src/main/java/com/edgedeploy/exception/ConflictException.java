package com.edgedeploy.exception;

/** The request is valid but clashes with current state (duplicate resource, illegal transition). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
