package com.example.hotelmanagement.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    private final AppProperties appProperties;

    @Bean
    public OpenAPI baseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(appProperties.getName() + " - REST API")
                        .version("v1.0.0")
                        .description("Reusable Modular Monolith Spring Boot REST API with JWT Authentication & RBAC")
                        .contact(new Contact().name("Backend Engineering Team")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME, new SecurityScheme()
                                .name(BEARER_AUTH_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Provide a valid JWT access token obtained from /api/v1/auth/login")));
    }

    @Bean
    public GroupedOpenApi authApiGroup() {
        return GroupedOpenApi.builder()
                .group("1-auth")
                .displayName("1. Authentication & Profile API")
                .pathsToMatch("/api/v1/auth/**")
                .build();
    }

    @Bean
    public GroupedOpenApi userManagementApiGroup() {
        return GroupedOpenApi.builder()
                .group("2-users")
                .displayName("2. User Management API (ADMIN)")
                .pathsToMatch("/api/v1/users/**")
                .build();
    }
}
