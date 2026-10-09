package com.edgedeploy.worker.framework;

import java.util.List;
import java.util.Set;

/**
 * What the worker learned about a checked-out repository.
 *
 * @param scripts        names of {@code package.json} scripts (e.g. build, start)
 * @param main           {@code package.json} "main" entry, or null
 * @param hasLockfile    whether {@link PackageManager#lockfile()} exists
 * @param manifestFiles  root files needed to install dependencies (package.json, lockfile, .npmrc...)
 * @param reason         why this framework was chosen, for the deployment log
 */
public record DetectedProject(
        Framework framework,
        PackageManager packageManager,
        Set<String> scripts,
        String main,
        boolean hasLockfile,
        List<String> manifestFiles,
        String reason) {

    public boolean hasScript(String name) {
        return scripts.contains(name);
    }
}
