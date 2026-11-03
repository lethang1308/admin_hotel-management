package com.example.hotelmanagement.modules.room.dto;

public record RoomStayResponse(
        String bookingCode,
        String guestName,
        String phone,
        java.time.LocalDate fromDate,
        java.time.LocalDate toDate,
        java.time.Instant checkIn,
        java.time.Instant checkOut,
        String status,
        String checkedInBy) {}
