package com.example.hotelmanagement.modules.room.dto;

public record RoomHistoryResponse(
        Long id,
        String oldStatus,
        String newStatus,
        String actorName,
        String reason,
        java.time.Instant changedAt) {}
