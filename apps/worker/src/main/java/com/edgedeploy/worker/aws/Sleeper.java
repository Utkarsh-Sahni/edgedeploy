package com.edgedeploy.worker.aws;

import org.springframework.stereotype.Component;

import java.time.Duration;

/** Waiting between polls; replaced by a no-op in tests so they run instantly. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    @Component
    class ThreadSleeper implements Sleeper {
        @Override
        public void sleep(Duration duration) throws InterruptedException {
            Thread.sleep(duration);
        }
    }
}
