package com.edgedeploy.worker.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real SDK client with EdgeDeploy's client configuration, talking to a scripted HTTP layer: proves which
 * failures are retried (with backoff) and that retries are bounded. No network, no AWS account.
 */
class AwsRetryTest {

    private static final String OK = """
            {"repositories":[{"repositoryName":"edgedeploy/p","repositoryUri":"123456789012.dkr.ecr.ap-south-1.amazonaws.com/edgedeploy/p"}]}""";

    @Test
    void retriesServiceUnavailableUntilItSucceeds() {
        ScriptedHttpClient http = new ScriptedHttpClient(response(503, "ServiceUnavailableException"), response(503,
                "ServiceUnavailableException"), response(200, null));

        assertThat(client(http, 3).describeRepositories(r -> r.repositoryNames("edgedeploy/p")).repositories()).hasSize(1);
        assertThat(http.calls()).isEqualTo(3);
    }

    @Test
    void retriesThrottling() {
        ScriptedHttpClient http = new ScriptedHttpClient(response(400, "ThrottlingException"), response(200, null));

        client(http, 3).describeRepositories(r -> r.repositoryNames("edgedeploy/p"));

        assertThat(http.calls()).isEqualTo(2);
    }

    @Test
    void retriesAreBounded() {
        ScriptedHttpClient http = new ScriptedHttpClient(response(500, "ServerException"));

        assertThatThrownBy(() -> client(http, 3).describeRepositories(r -> r.repositoryNames("edgedeploy/p")))
                .isInstanceOf(AwsServiceException.class);
        assertThat(http.calls()).isEqualTo(3);
    }

    @Test
    void neverRetriesAccessDenied() {
        ScriptedHttpClient http = new ScriptedHttpClient(response(400, "AccessDeniedException"), response(200, null));

        assertThatThrownBy(() -> client(http, 3).describeRepositories(r -> r.repositoryNames("edgedeploy/p")))
                .isInstanceOf(AwsServiceException.class);
        assertThat(http.calls()).isEqualTo(1);
    }

    private static EcrClient client(SdkHttpClient http, int maxAttempts) {
        return EcrClient.builder()
                .httpClient(http)
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")))
                .overrideConfiguration(AwsConfig.clientConfiguration(maxAttempts))
                .build();
    }

    private record Scripted(int status, String errorType) {
    }

    private static Scripted response(int status, String errorType) {
        return new Scripted(status, errorType);
    }

    /** Plays back scripted responses; the last one repeats. */
    private static final class ScriptedHttpClient implements SdkHttpClient {
        private final Deque<Scripted> script;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedHttpClient(Scripted... responses) {
            this.script = new ArrayDeque<>(List.of(responses));
        }

        int calls() {
            return calls.get();
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    calls.incrementAndGet();
                    Scripted next = script.size() > 1 ? script.poll() : script.peek();
                    String body = next.status() == 200 ? OK
                            : "{\"__type\":\"" + next.errorType() + "\",\"message\":\"scripted\"}";
                    SdkHttpResponse response = SdkHttpResponse.builder().statusCode(next.status())
                            .putHeader("Content-Type", "application/x-amz-json-1.1")
                            .putHeader("x-amzn-ErrorType", next.errorType() == null ? "" : next.errorType())
                            .build();
                    return HttpExecuteResponse.builder().response(response)
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
                            .build();
                }

                @Override
                public void abort() {
                }
            };
        }

        @Override
        public void close() {
        }

        @Override
        public String clientName() {
            return "scripted";
        }
    }
}
