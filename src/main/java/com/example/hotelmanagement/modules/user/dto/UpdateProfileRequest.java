package com.example.hotelmanagement.modules.user.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record UpdateProfileRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank
                @Pattern(
                        regexp = "^(?:0|\\+84)[35789][0-9]{8}$",
                        message = "Số điện thoại Việt Nam không hợp lệ")
                String phone,
        @Past LocalDate dateOfBirth,
        @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
        @Size(max = 500) String address) {}
