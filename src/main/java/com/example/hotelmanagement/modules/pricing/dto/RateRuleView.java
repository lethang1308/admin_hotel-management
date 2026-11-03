package com.example.hotelmanagement.modules.pricing.dto;

public record RateRuleView(
        Long id,
        int version,
        RateRuleForm form,
        boolean referenced,
        boolean latest,
        java.util.List<String> roomTypeNames) {}
