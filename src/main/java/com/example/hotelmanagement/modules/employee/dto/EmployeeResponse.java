package com.example.hotelmanagement.modules.employee.dto;

import com.example.hotelmanagement.modules.user.entity.UserStatus;

import java.time.*;
import java.util.Set;

public record EmployeeResponse(
        Long id,
        Long userId,
        String employeeCode,
        String fullName,
        String email,
        String phone,
        String position,
        LocalDate hiredOn,
        LocalDate endedOn,
        UserStatus status,
        Instant lockedUntil,
        Instant lastLoginAt,
        Set<String> roles) {}
