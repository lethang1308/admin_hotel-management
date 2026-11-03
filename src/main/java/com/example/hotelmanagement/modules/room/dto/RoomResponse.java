package com.example.hotelmanagement.modules.room.dto;

public record RoomResponse(
        Long id,
        String roomNumber,
        Long roomTypeId,
        String roomTypeName,
        java.math.BigDecimal basePrice,
        Long floorId,
        String floorName,
        String areaName,
        int floorSort,
        int floorNumber,
        com.example.hotelmanagement.modules.room.entity.RoomStatus status,
        java.time.LocalDate sellFrom,
        java.time.LocalDate sellUntil,
        String notes,
        int version,
        String guestName,
        java.time.LocalDate expectedCheckout) {}
