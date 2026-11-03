package com.example.hotelmanagement.modules.auth.dto;

import com.example.hotelmanagement.modules.user.entity.UserStatus;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class AccountStatusForm {
    @NotNull(message="{cms.validation.required}") private UserStatus status;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime lockedUntil;

    @NotBlank(message="{cms.validation.required}")
    @Size(max = 1000, message="{cms.validation.size}")
    private String reason;
}
