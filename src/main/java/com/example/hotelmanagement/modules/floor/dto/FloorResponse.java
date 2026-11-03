package com.example.hotelmanagement.modules.floor.dto;

public record FloorResponse(
        Long id,
        String code,
        String name,
        String areaName,
        Integer floorNumber,
        Integer sortOrder,
        boolean active,
        long roomCount) {}
