package com.example.hotelmanagement.modules.auth.dto;

import com.example.hotelmanagement.modules.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "Authentication token pair and current user profile")
public class AuthTokenResponse {

    @Schema(description = "Signed JWT access token for Authorization: Bearer <token>")
    private final String accessToken;

    @Schema(description = "Opaque single-use refresh token (rotated on every refresh)")
    private final String refreshToken;

    @Schema(description = "Authorization header scheme", example = "Bearer")
    private final String tokenType;

    @Schema(description = "Access token lifetime in seconds", example = "900")
    private final long expiresIn;

    @Schema(description = "Authenticated user profile")
    private final UserResponse user;
}
