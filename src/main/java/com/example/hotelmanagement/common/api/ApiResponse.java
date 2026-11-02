package com.example.hotelmanagement.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard envelope for successful REST API responses")
public class ApiResponse<T> {

    @Schema(description = "Indicates whether the operation succeeded", example = "true")
    private final boolean success;

    @Schema(description = "Human-readable status message", example = "Operation successful")
    private final String message;

    @Schema(description = "Response payload")
    private final T data;

    @Schema(description = "UTC timestamp of the response", example = "2026-11-02T12:00:00Z")
    private final Instant timestamp;

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return ok("Operation successful", data);
    }

    public static ApiResponse<Void> okMessage(String message) {
        return ApiResponse.<Void>builder()
                .success(true)
                .message(message)
                .timestamp(Instant.now())
                .build();
    }
}
