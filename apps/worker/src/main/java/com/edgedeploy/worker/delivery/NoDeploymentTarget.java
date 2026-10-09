package com.edgedeploy.worker.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Consumer;

/** Local mode: no runtime; deployments finish once the image is built. */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoDeploymentTarget implements DeploymentTargetService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Rollout start(Release release, Consumer<String> progress) {
        throw new UnsupportedOperationException("No deployment target configured");
    }

    @Override
    public Running awaitStable(Rollout rollout, Duration timeout, Consumer<String> progress) {
        throw new UnsupportedOperationException("No deployment target configured");
    }

    @Override
    public void verifyHealth(Rollout rollout, Duration timeout, Consumer<String> progress) {
        throw new UnsupportedOperationException("No deployment target configured");
    }

    @Override
    public void restore(Rollout rollout, String previousVersion, Consumer<String> progress) {
        // nothing was deployed
    }
}
