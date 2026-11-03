package com.example.hotelmanagement.modules.audit.service;

import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.audit.dto.*;
import com.example.hotelmanagement.modules.audit.entity.AuditLog;
import com.example.hotelmanagement.modules.audit.event.AuditRecordEvent;
import com.example.hotelmanagement.modules.audit.repository.AuditLogRepository;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;
    private final ApplicationEventPublisher events;
    private final JsonMapper mapper;

    @Transactional
    public void record(
            String action,
            String type,
            Object id,
            Map<String, ?> before,
            Map<String, ?> after,
            String reason) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Long actor =
                auth != null && auth.getPrincipal() instanceof CustomUserDetails d
                        ? d.getId()
                        : null;
        if (actor == null
                && ("LOGIN".equals(action) || "LOGOUT".equals(action))
                && id instanceof Long userId) actor = userId;
        String ip = null, requestId = null;
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            ip = attributes.getRequest().getRemoteAddr();
            requestId = org.slf4j.MDC.get("correlationId");
        }
        events.publishEvent(
                new AuditRecordEvent(
                        actor,
                        action,
                        type,
                        String.valueOf(id),
                        clean(before),
                        clean(after),
                        reason,
                        ip,
                        requestId,
                        Instant.now()));
    }

    // Deliberately synchronous: joins the mutation transaction; a rollback also rolls back its
    // audit.
    @EventListener
    public void persist(AuditRecordEvent event) {
        repository.save(
                AuditLog.builder()
                        .actorUserId(event.actorId())
                        .action(event.action())
                        .entityType(event.entityType())
                        .entityId(event.entityId())
                        .beforeData(json(event.before()))
                        .afterData(json(event.after()))
                        .reason(event.reason())
                        .ipAddress(event.ip())
                        .requestId(event.requestId())
                        .createdAt(event.occurredAt())
                        .build());
    }

    private Map<String, ?> clean(Map<String, ?> input) {
        if (input == null) return null;
        Map<String, Object> safe = new LinkedHashMap<>();
        input.forEach(
                (k, v) -> {
                    String lower = k.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                    if ((!lower.contains("password") || lower.equals("passwordchangedat"))
                            && !lower.contains("token")
                            && !lower.contains("secret")
                            && !lower.contains("encrypted")
                            && !lower.contains("documentnumber")
                            && !lower.endsWith("hash")
                            && !lower.contains("credential")
                            && !lower.contains("encryptionkey")
                            && !lower.contains("signingkey")
                            && !lower.equals("otp")
                            && !lower.equals("authorization")) safe.put(k, cleanValue(v));
                });
        return safe;
    }

    private Object cleanValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> named = new LinkedHashMap<>();
            map.forEach((k, v) -> named.put(String.valueOf(k), v));
            return clean(named);
        }
        if (value instanceof Collection<?> values)
            return values.stream().map(this::cleanValue).toList();
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.TemporalAccessor) return value;
        return "[unsupported snapshot value]";
    }

    private String json(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    private AuditLogResponse view(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getActorUserId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getReason(),
                log.getIpAddress(),
                log.getRequestId(),
                log.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(
            int page,
            int size,
            String action,
            String type,
            Long actor,
            LocalDate from,
            LocalDate to) {
        return PageResponse.from(
                repository.findAll(
                        (root, q, cb) -> {
                            var predicates =
                                    new ArrayList<jakarta.persistence.criteria.Predicate>();
                            if (action != null && !action.isBlank())
                                predicates.add(cb.equal(root.get("action"), action.trim()));
                            if (type != null && !type.isBlank())
                                predicates.add(cb.equal(root.get("entityType"), type.trim()));
                            if (actor != null)
                                predicates.add(cb.equal(root.get("actorUserId"), actor));
                            var zone = ZoneId.of("Asia/Ho_Chi_Minh");
                            if (from != null)
                                predicates.add(
                                        cb.greaterThanOrEqualTo(
                                                root.get("createdAt"),
                                                from.atStartOfDay(zone).toInstant()));
                            if (to != null)
                                predicates.add(
                                        cb.lessThan(
                                                root.get("createdAt"),
                                                to.plusDays(1).atStartOfDay(zone).toInstant()));
                            return cb.and(
                                    predicates.toArray(
                                            jakarta.persistence.criteria.Predicate[]::new));
                        },
                        PageRequest.of(
                                Math.max(0, page),
                                Math.min(100, Math.max(1, size)),
                                Sort.by(Sort.Direction.DESC, "id"))),
                this::view);
    }

    @Transactional(readOnly = true)
    public AuditLogResponse get(Long id) {
        return view(required(id));
    }

    private AuditLog required(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditLog", id));
    }

    @Transactional(readOnly = true)
    public List<AuditDiffField> diff(Long id) {
        AuditLog log = required(id);
        Map<String, Object> before = parse(log.getBeforeData()), after = parse(log.getAfterData());
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        return fields.stream()
                .map(
                        k ->
                                new AuditDiffField(
                                        k,
                                        Objects.toString(before.get(k), "-"),
                                        Objects.toString(after.get(k), "-"),
                                        !Objects.equals(before.get(k), after.get(k))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        return json == null ? Map.of() : mapper.readValue(json, Map.class);
    }
}
