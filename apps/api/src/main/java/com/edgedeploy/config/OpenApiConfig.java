package com.edgedeploy.config;

import com.edgedeploy.security.SecurityConfig;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String SESSION = "session";
    static final String CSRF = "csrf";
    private static final String PUBLIC_PATH = "/api/auth/csrf";

    @Bean
    OpenAPI edgeDeployOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EdgeDeploy API")
                        .version("v1")
                        .description("""
                                Deploy GitHub repositories. All `/api` endpoints require a session created by signing \
                                in with GitHub (`GET /oauth2/authorization/github` in a browser). Errors are RFC 9457 \
                                ProblemDetail documents with a `requestId`."""))
                .components(new Components()
                        .addSecuritySchemes(SESSION, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("EDGEDEPLOY_SESSION")
                                .description("HttpOnly session cookie set after GitHub sign-in"))
                        .addSecuritySchemes(CSRF, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(SecurityConfig.CSRF_HEADER)
                                .description("Required on POST/PATCH/DELETE: the value of the XSRF-TOKEN cookie")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION).addList(CSRF));
    }

    /** Every authenticated operation can answer 401, 403 and 500; declare them once here. */
    @Bean
    OpenApiCustomizer commonErrorResponses() {
        return openApi -> {
            Content problem = new Content().addMediaType("application/problem+json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));
            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/") || path.equals(PUBLIC_PATH)) {
                    return;
                }
                item.readOperations().forEach(operation -> {
                    operation.getResponses().putIfAbsent("401", new ApiResponse()
                            .description("Not signed in, or GitHub authorization expired (`code`)").content(problem));
                    operation.getResponses().putIfAbsent("403", new ApiResponse()
                            .description("Missing/invalid CSRF token on a mutating request").content(problem));
                    operation.getResponses().putIfAbsent("500", new ApiResponse()
                            .description("Unexpected error; quote the requestId").content(problem));
                });
            });
        };
    }
}
