package com.example.hotelmanagement.modules.audit.dto;

public record AuditDiffField(
        String field, String beforeValue, String afterValue, boolean changed) {}
