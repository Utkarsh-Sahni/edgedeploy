package com.edgedeploy.worker.docker;

import com.edgedeploy.worker.framework.Framework;
import org.springframework.stereotype.Component;

/** Create React App: {@code react-scripts build} writes to {@code build/}. */
@Component
class ReactBuildStrategy extends StaticSiteBuildStrategy {

    @Override
    public Framework framework() {
        return Framework.REACT;
    }

    @Override
    String outputDirectory() {
        return "build";
    }
}
