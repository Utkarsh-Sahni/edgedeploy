package com.edgedeploy.exception;

/**
 * A syntactically valid request that fails a business check tied to one field (e.g. the branch does
 * not exist on GitHub). Rendered like Bean Validation errors, so forms can show it inline.
 */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
