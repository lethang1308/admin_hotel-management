package com.example.hotelmanagement.modules.user.dto;

import com.example.hotelmanagement.modules.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Admin request payload to activate, deactivate, or lock a user account")
public class UpdateUserStatusRequest {

    @NotNull(message = "Status is required")
    @Schema(description = "Target user status", example = "INACTIVE")
    private UserStatus status;
}
