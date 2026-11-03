package com.example.hotelmanagement.modules.room.dto;

public record RoomGridResponse(
        java.util.List<FloorGroup> floors, java.util.Map<String, Long> counts, long total) {
    public record FloorGroup(
            Long id, String name, String areaName, java.util.List<RoomResponse> rooms) {}
}
