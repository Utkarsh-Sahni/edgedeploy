package com.edgedeploy.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Typed view of the {@code edgedeploy.*} configuration tree, validated at startup. */
@Validated
@ConfigurationProperties("edgedeploy")
public record EdgeDeployProperties(
        @NotEmpty List<String> webOrigins,
        /* Base URL of the dashboard; OAuth success/failure redirects go here. */
        @NotBlank String webUrl,
        @Valid @NotNull Cookie cookie,
        @Valid @NotNull Encryption encryption,
        @Valid @NotNull GitHub github,
        @Valid @NotNull Kafka kafka,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Sse sse) {

    /**
     * @param domain parent domain shared by web and api in production (e.g. {@code edgedeploy.app}) so the
     *               dashboard can read the XSRF cookie; blank means host-only (fine for localhost)
     */
    public record Cookie(String domain, boolean secure) {
    }

    /** @param key base64-encoded 256-bit AES key ({@code openssl rand -base64 32}) */
    public record Encryption(@NotBlank String key) {
    }

    public record GitHub(@NotBlank String apiBaseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) int replicas, @NotBlank String statusConsumerGroup) {
    }

    public record Outbox(@NotNull Duration pollInterval, @Min(1) int batchSize, @NotNull Duration sendTimeout) {
    }

    public record Sse(@NotNull Duration timeout) {
    }
}
