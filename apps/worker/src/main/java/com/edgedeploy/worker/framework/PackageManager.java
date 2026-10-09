package com.edgedeploy.worker.framework;

/**
 * JavaScript package managers and how to drive them non-interactively inside the build container.
 * pnpm and Yarn are provided by Corepack, which ships with the official Node images.
 */
public enum PackageManager {
    NPM("package-lock.json"),
    PNPM("pnpm-lock.yaml"),
    YARN("yarn.lock"),
    /** Yarn 2+ ("berry"), recognised by {@code .yarnrc.yml}. */
    YARN_BERRY("yarn.lock");

    private final String lockfile;

    PackageManager(String lockfile) {
        this.lockfile = lockfile;
    }

    public String lockfile() {
        return lockfile;
    }

    /** Reproducible install when a lockfile exists; plain install otherwise. */
    public String installCommand(boolean hasLockfile) {
        return switch (this) {
            case NPM -> hasLockfile ? "npm ci" : "npm install";
            case PNPM -> "corepack enable && pnpm install" + (hasLockfile ? " --frozen-lockfile" : "");
            case YARN -> "corepack enable && yarn install" + (hasLockfile ? " --frozen-lockfile" : "");
            case YARN_BERRY -> "corepack enable && yarn install" + (hasLockfile ? " --immutable" : "");
        };
    }

    public String runScript(String script) {
        return switch (this) {
            case NPM -> "npm run " + script;
            case PNPM -> "pnpm run " + script;
            case YARN, YARN_BERRY -> "yarn run " + script;
        };
    }

    /** Removes devDependencies after the build to shrink the runtime image, where supported cheaply. */
    public String pruneCommand() {
        return switch (this) {
            case NPM -> "npm prune --omit=dev";
            case PNPM -> "pnpm prune --prod";
            case YARN, YARN_BERRY -> null;
        };
    }
}
