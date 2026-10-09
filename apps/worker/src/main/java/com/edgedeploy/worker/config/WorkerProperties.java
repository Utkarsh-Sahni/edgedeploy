package com.edgedeploy.worker.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties("edgedeploy")
public record WorkerProperties(
        /* What the worker does with deployment requests; see DeploymentRequestHandler. */
        @NotNull Mode mode,
        @Valid @NotNull Kafka kafka,
        @Valid @NotNull Timeouts timeouts,
        @Valid @NotNull Workspace workspace,
        @Valid @NotNull Git git,
        @Valid @NotNull Docker docker,
        @Valid @NotNull Logs logs,
        @Valid @NotNull Encryption encryption) {

    public enum Mode {
        /** Clone, detect, generate a Dockerfile and build the image (Phase 3). */
        BUILD,
        /** Verify and record receipt only; useful where git/Docker are unavailable. */
        ACKNOWLEDGE
    }

    public record Kafka(@Min(1) int partitions, @Min(1) int replicas, @Valid @NotNull Retry retry) {
    }

    /** Exponential backoff for transient failures before a record is dead-lettered. */
    public record Retry(@Min(0) int maxRetries, @NotNull Duration initialInterval,
                        @DecimalMin("1.0") double multiplier, @NotNull Duration maxInterval) {
    }

    /**
     * Every step is also capped by what remains of {@code deployment}.
     *
     * @param deployment   hard limit for one whole deployment, from claim to final status
     * @param dockerBuild  limit for a single {@code docker build}
     * @param gitOperation limit for each git command (fetch, checkout, ...)
     * @param imagePush    limit for pushing the image to the registry
     * @param rollout      limit for the deployment target to roll out the new version (ECS stabilization)
     * @param healthCheck  limit for the application to answer health checks once rolled out
     */
    public record Timeouts(@NotNull Duration deployment, @NotNull Duration dockerBuild, @NotNull Duration gitOperation,
                           @NotNull Duration imagePush, @NotNull Duration rollout, @NotNull Duration healthCheck) {
    }

    public record Workspace(@NotNull Path baseDir) {
    }

    /**
     * @param deployToken       token with read access to repositories (GITHUB_DEPLOY_TOKEN); blank = public repos only
     * @param allowFileProtocol allow {@code file://} remotes; tests only
     */
    public record Git(@NotBlank String executable, @NotBlank String baseUrl, String deployToken, boolean allowFileProtocol) {

        /** Never print the token, even if the whole configuration is logged. */
        @Override
        public String toString() {
            return "Git[executable=" + executable + ", baseUrl=" + baseUrl + ", deployToken="
                    + (deployToken == null || deployToken.isBlank() ? "<none>" : "<redacted>")
                    + ", allowFileProtocol=" + allowFileProtocol + "]";
        }
    }

    /**
     * @param appPort  port Node.js / Next.js containers listen on (exported as PORT); static sites use nginx on 8080
     * @param platform optional {@code --platform} for builds (e.g. linux/amd64); blank = the daemon's native platform.
     *                 The deployment target runs the image on whatever architecture it was built for.
     */
    public record Docker(
            @NotBlank String executable,
            @NotBlank @Pattern(regexp = "^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$") String imageRepository,
            @NotBlank String nodeImage,
            @NotBlank String staticRuntimeImage,
            @Min(1024) @Max(65535) int appPort,
            @Pattern(regexp = "^$|^linux/(amd64|arm64)$") String platform) {
    }

    /**
     * @param key base64 AES-256 key shared with the api (ENCRYPTION_KEY); needed to decrypt project
     *            environment variables before deploying. Blank disables environment variable injection.
     */
    public record Encryption(String key) {

        @Override
        public String toString() {
            return "Encryption[key=" + (key == null || key.isBlank() ? "<none>" : "<redacted>") + "]";
        }
    }

    /**
     * @param maxOutputLines build output lines persisted per deployment; the tail is kept beyond that
     */
    public record Logs(@Min(100) int maxOutputLines, @Min(1) int flushLines, @NotNull Duration flushInterval) {
    }
}
