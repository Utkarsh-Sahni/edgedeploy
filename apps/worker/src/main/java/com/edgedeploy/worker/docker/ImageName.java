package com.edgedeploy.worker.docker;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code {repository}/{projectId}:{commitSha}}, e.g. {@code edgedeploy/8f42…:a83f12c…}. Built only from a
 * validated repository prefix, a UUID and a hex SHA, so it is always a valid, injection-free reference.
 */
public record ImageName(String reference) {

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");

    public static ImageName of(String repositoryPrefix, UUID projectId, String commitSha) {
        if (!SHA.matcher(commitSha).matches()) {
            throw new IllegalArgumentException("Commit SHA must be 40 lowercase hex characters");
        }
        return new ImageName(repositoryPrefix + "/" + projectId + ":" + commitSha);
    }

    @Override
    public String toString() {
        return reference;
    }
}
