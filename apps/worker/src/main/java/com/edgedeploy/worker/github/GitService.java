package com.edgedeploy.worker.github;

import com.edgedeploy.worker.workspace.Workspace;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Retrieves exactly the requested source code into a deployment workspace.
 */
public interface GitService {

    /**
     * @param repository GitHub {@code owner/name}
     * @param commitSha  full SHA to build; null to build the current tip of {@code branch}
     * @param timeout    limit for each individual git command
     */
    record Request(String repository, String branch, String commitSha, Workspace workspace, Duration timeout,
                   BooleanSupplier cancelled) {
    }

    /** Creates a repository in {@code workspace.source()} and fetches the requested commit (or branch tip). */
    void cloneRepository(Request request) throws GitException;

    /**
     * Checks out the requested commit (detached) and verifies {@code HEAD} equals it.
     *
     * @return the verified full SHA of {@code HEAD}
     */
    String checkoutCommit(Request request) throws GitException;

    /** Removes the checked-out source. Never follows symbolic links. */
    void cleanup(Workspace workspace);
}
