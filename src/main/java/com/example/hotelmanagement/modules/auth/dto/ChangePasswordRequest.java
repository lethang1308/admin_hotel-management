package com.example.hotelmanagement.modules.auth.dto;

import com.example.hotelmanagement.common.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload to change the current authenticated user's password")
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    @Schema(description = "Existing password", example = "Welcome123")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    @StrongPassword
    @Schema(description = "New password (8-72 characters, at least 1 letter and 1 number)", example = "NewSecure456")
    private String newPassword;
}
