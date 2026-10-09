package com.edgedeploy.github;

import com.edgedeploy.config.EdgeDeployProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class GitHubConfig {

    static final String API_VERSION = "2022-11-28";

    @Bean
    RestClient gitHubRestClient(RestClient.Builder builder, EdgeDeployProperties properties) {
        EdgeDeployProperties.GitHub github = properties.github();
        return builder
                .baseUrl(github.apiBaseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(github.connectTimeout())
                        .withReadTimeout(github.readTimeout())))
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .defaultHeader(HttpHeaders.USER_AGENT, "EdgeDeploy")
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw GitHubClient.toException(response.getStatusCode(), response.getHeaders());
                })
                .build();
    }
}
