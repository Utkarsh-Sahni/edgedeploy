package com.edgedeploy.worker.aws;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One HTTP GET against a deployed application; replaceable in tests. */
@FunctionalInterface
public interface HttpHealthProbe {

    /** @return the HTTP status code; redirects are not followed (a 3xx already proves the app answers) */
    int status(URI uri, Duration timeout) throws IOException, InterruptedException;

    @Component
    class JdkHttpHealthProbe implements HttpHealthProbe {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        @Override
        public int status(URI uri, Duration timeout) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "EdgeDeploy-HealthCheck").GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
