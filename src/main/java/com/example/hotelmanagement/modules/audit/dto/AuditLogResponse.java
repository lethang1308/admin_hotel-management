package com.example.hotelmanagement.modules.audit.dto;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String action,
        String entityType,
        String entityId,
        String reason,
        String ipAddress,
        String requestId,
        Instant createdAt) {}
