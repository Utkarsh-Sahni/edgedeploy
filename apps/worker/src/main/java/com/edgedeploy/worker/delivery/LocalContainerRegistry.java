package com.edgedeploy.worker.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local mode: images stay in the worker's Docker daemon; nothing is pushed. */
@Component
@ConditionalOnProperty(prefix = "edgedeploy.aws", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalContainerRegistry implements ContainerRegistryService {

    @Override
    public boolean remote() {
        return false;
    }

    @Override
    public PublishedImage publish(PublishRequest request) {
        String reference = request.image().name().reference();
        return new PublishedImage(reference, reference, null, false);
    }

    @Override
    public String description() {
        return "local Docker daemon (not pushed)";
    }
}
