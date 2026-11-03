package com.example.hotelmanagement.modules.customer.dto;

import java.time.*;

public record CustomerResponse(
        Long id,
        Long userId,
        String customerCode,
        String fullName,
        String email,
        String phone,
        LocalDate dateOfBirth,
        String nationalityCode,
        String address,
        String notes,
        String documentType,
        String maskedDocument,
        Instant createdAt) {}
