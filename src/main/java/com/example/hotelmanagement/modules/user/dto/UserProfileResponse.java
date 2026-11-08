package com.example.hotelmanagement.modules.user.dto;

import java.time.LocalDate;
import java.util.Set;

public record UserProfileResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        LocalDate dateOfBirth,
        String gender,
        String address,
        String nationalityCode,
        String customerCode,
        Set<String> roles) {}
