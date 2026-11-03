package com.example.hotelmanagement.modules.room.dto;

public record RoomBlockResponse(
        Long id,
        Long roomId,
        String roomNumber,
        String blockType,
        java.time.LocalDate fromDate,
        java.time.LocalDate toDate,
        String status,
        String reason,
        String createdBy,
        java.time.Instant createdAt,
        java.time.Instant releasedAt) {}
