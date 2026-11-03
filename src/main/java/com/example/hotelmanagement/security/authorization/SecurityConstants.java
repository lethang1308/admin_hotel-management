package com.example.hotelmanagement.security.authorization;

public final class SecurityConstants {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_RECEPTIONIST = "RECEPTIONIST";
    public static final String ROLE_USER = "USER";

    public static final String BEARER_PREFIX = "Bearer ";
    public static final String AUTH_HEADER = "Authorization";
    public static final String AUTH_ERROR_CODE_ATTR = "AUTH_ERROR_CODE";

    private SecurityConstants() {
    }
}
