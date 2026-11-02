package com.example.hotelmanagement.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String name = "Enterprise Management System";
    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Pagination pagination = new Pagination();
    private final OpenApi openapi = new OpenApi();
    private final Seed seed = new Seed();

    @Getter
    @Setter
    public static class Jwt {
        private String signingKey;
        private String issuer = "enterprise-backend-base";
        private long accessExpirationSeconds = 900L;
        private long refreshExpirationSeconds = 604800L;
        private boolean allowEphemeralDevKey = false;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of();
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of("Authorization", "Content-Type", "Accept", "X-Correlation-Id");
        private List<String> exposedHeaders = List.of("X-Correlation-Id");
        private boolean allowCredentials = true;
        private long maxAgeSeconds = 3600L;
    }

    @Getter
    @Setter
    public static class Pagination {
        private int defaultPageSize = 10;
        private int maxPageSize = 100;
    }

    @Getter
    @Setter
    public static class OpenApi {
        private boolean publicAccess = false;
    }

    @Getter
    @Setter
    public static class Seed {
        private final Admin admin = new Admin();

        @Getter
        @Setter
        public static class Admin {
            private boolean enabled = false;
            private String email;
            private String password;
            private String fullName = "System Administrator";
        }
    }
}
