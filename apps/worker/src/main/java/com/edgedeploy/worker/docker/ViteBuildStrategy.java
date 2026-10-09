package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.Framework;
import org.springframework.stereotype.Component;

/** Vite (React, Vue, Svelte, vanilla): {@code vite build} writes to {@code dist/}. */
@Component
class ViteBuildStrategy extends StaticSiteBuildStrategy {

    @Override
    public Framework framework() {
        return Framework.VITE;
    }

    @Override
    String outputDirectory() {
        return "dist";
    }
}
