package com.edgedeploy.exception;

import java.util.UUID;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, UUID id) {
        super(resource + " " + id + " not found");
    }

    public ResourceNotFoundException(String resource, String name) {
        super(resource + " " + name + " not found");
    }
}
