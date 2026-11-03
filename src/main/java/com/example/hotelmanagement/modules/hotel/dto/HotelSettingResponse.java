package com.example.hotelmanagement.modules.hotel.dto;

public record HotelSettingResponse(
        Long id,
        String name,
        String description,
        String address,
        String hotline,
        String email,
        String logoUrl,
        String timezone,
        String currency,
        java.time.LocalTime standardCheckIn,
        java.time.LocalTime standardCheckOut,
        Integer bookingHoldMinutes,
        java.math.BigDecimal defaultTaxPercent,
        Long defaultDepositPolicyId,
        Long defaultCancellationPolicyId) {}
