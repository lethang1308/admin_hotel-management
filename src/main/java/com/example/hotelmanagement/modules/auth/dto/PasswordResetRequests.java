package com.example.hotelmanagement.modules.auth.dto;

import com.example.hotelmanagement.common.validation.StrongPassword;

import jakarta.validation.constraints.*;

public final class PasswordResetRequests {
    private PasswordResetRequests() {}

    public record Forgot(@NotBlank @Email @Size(max = 150) String email) {}

    public record Reset(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{64}") String token,
            @NotBlank @StrongPassword String newPassword) {}
}
