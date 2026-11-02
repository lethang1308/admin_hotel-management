package com.example.hotelmanagement.modules.user.dto;

import com.example.hotelmanagement.modules.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Public user representation (never exposes password or token data)")
public class UserResponse {

    @Schema(description = "Unique user identifier", example = "1")
    private final Long id;

    @Schema(description = "User full name", example = "Nguyen Van A")
    private final String fullName;

    @Schema(description = "Unique email address", example = "user@example.com")
    private final String email;

    @Schema(description = "Current account status", example = "ACTIVE")
    private final UserStatus status;

    @Schema(description = "Assigned RBAC role codes", example = "[\"USER\"]")
    private final Set<String> roles;

    @Schema(description = "UTC creation timestamp", example = "2026-11-02T12:00:00Z")
    private final Instant createdAt;

    @Schema(description = "UTC last update timestamp", example = "2026-11-02T12:00:00Z")
    private final Instant updatedAt;
}
