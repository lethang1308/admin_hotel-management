package com.example.hotelmanagement.modules.audit.event;

import java.time.Instant;
import java.util.Map;

public record AuditRecordEvent(
        Long actorId,
        String action,
        String entityType,
        String entityId,
        Map<String, ?> before,
        Map<String, ?> after,
        String reason,
        String ip,
        String requestId,
        Instant occurredAt) {}
