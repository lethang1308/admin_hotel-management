package com.example.hotelmanagement.modules.user.dto;

import com.example.hotelmanagement.common.validation.StrongPassword;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
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
@Schema(description = "Admin request payload to create a new user account")
public class CreateUserRequest {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 120, message = "Full name must be between 2 and 120 characters")
    @Schema(description = "Full name of the user", example = "Tran Thi B")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    @Schema(description = "Unique email address", example = "staff@example.com")
    private String email;

    @NotBlank(message = "Password is required")
    @StrongPassword
    @Schema(description = "Initial password (8-72 chars, at least 1 letter and 1 digit)", example = "SecurePass123")
    private String password;

    @Schema(description = "Initial account status (defaults to ACTIVE if omitted)", example = "ACTIVE")
    private UserStatus status;

    @Schema(description = "Role codes to assign (defaults to [\"USER\"] if omitted)", example = "[\"USER\"]")
    private Set<String> roles;
}
