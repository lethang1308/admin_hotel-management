package com.example.hotelmanagement.modules.auth.dto;

import com.example.hotelmanagement.common.validation.StrongPassword;

import jakarta.validation.constraints.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class CmsPasswordForm {
    @Size(max = 72, message="{cms.validation.size}")
    private String currentPassword;

    @StrongPassword(message="{cms.error.strongPassword}") private String newPassword;

    @NotBlank(message="{cms.validation.required}")
    @Size(max = 72, message="{cms.validation.size}")
    private String confirmPassword;

    @Size(max = 1000, message="{cms.validation.size}")
    private String reason;

    @AssertTrue(message = "{cms.error.passwordConfirmation}")
    public boolean isConfirmationMatching() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }
}
