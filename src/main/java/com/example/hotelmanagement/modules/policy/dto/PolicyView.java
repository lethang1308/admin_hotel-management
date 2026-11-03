package com.example.hotelmanagement.modules.policy.dto;

public record PolicyView<T>(
        Long id, int version, T form, boolean referenced, boolean latest, boolean hotelDefault) {}
