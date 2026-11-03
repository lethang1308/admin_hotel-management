package com.example.hotelmanagement.modules.roomtype.dto;

public record RoomTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        java.math.BigDecimal basePrice,
        java.math.BigDecimal areaSqm,
        Integer maxAdults,
        Integer maxChildren,
        Integer maxOccupancy,
        String bedDescription,
        boolean featured,
        boolean active,
        String coverUrl,
        java.util.List<com.example.hotelmanagement.modules.amenity.dto.AmenityResponse>
                amenities) {}
