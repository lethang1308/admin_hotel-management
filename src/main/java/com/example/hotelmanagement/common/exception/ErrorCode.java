package com.example.hotelmanagement.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST, "Validation Failed", "One or more request fields are invalid"),
    PAYLOAD_TOO_LARGE(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "Payload Too Large",
            "Ảnh tải lên vượt quá dung lượng cho phép (5 MB)"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed or invalid request"),
    INVALID_PAGINATION(
            HttpStatus.BAD_REQUEST,
            "Invalid Pagination Parameters",
            "Invalid pagination parameters"),
    INVALID_SORT_PARAMETER(
            HttpStatus.BAD_REQUEST, "Invalid Sort Parameter", "Invalid sort parameter"),
    BUSINESS_RULE_VIOLATION(
            HttpStatus.BAD_REQUEST,
            "Business Rule Violation",
            "Operation violates a business rule"),

    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            "Authentication is required to access this resource"),
    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED, "Invalid Credentials", "Invalid email or password"),
    ACCOUNT_DISABLED(
            HttpStatus.FORBIDDEN,
            "Account Disabled",
            "User account is currently inactive or locked"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token Expired", "Authentication token has expired"),
    TOKEN_INVALID(
            HttpStatus.UNAUTHORIZED,
            "Invalid Token",
            "Authentication token is invalid or malformed"),
    TOKEN_REVOKED(
            HttpStatus.UNAUTHORIZED, "Token Revoked", "Refresh token has been revoked or rotated"),

    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "Access Denied",
            "You do not have permission to perform this action"),
    RESOURCE_NOT_FOUND(
            HttpStatus.NOT_FOUND, "Resource Not Found", "Requested resource was not found"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Duplicate Resource", "Resource already exists"),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "HTTP method is not supported"),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Server Error",
            "An unexpected internal error occurred");

    private final HttpStatus status;
    private final String title;
    private final String defaultMessage;
}
