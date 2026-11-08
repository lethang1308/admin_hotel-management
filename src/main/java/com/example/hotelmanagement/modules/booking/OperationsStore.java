package com.example.hotelmanagement.modules.booking;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.util.CmsActor;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.reception.ReceptionOutboxDispatcher;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
@RequiredArgsConstructor
public class OperationsStore {
    public static final BigDecimal ZERO = BigDecimal.ZERO;
    private final JdbcTemplate jdbc;
    private final CmsMessages messages;
    private final AuditLogService audit;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    public record Row(Map<String, Object> data) {
        public String s(String key) {
            Object value = data.get(key);
            return value instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : Objects.toString(value, null);
        }

        public Long id(String key) {
            return data.get(key) instanceof Number n ? n.longValue() : null;
        }

        public int n(String key) {
            return Math.toIntExact(id(key));
        }

        public BigDecimal money(String key) {
            return (BigDecimal) data.get(key);
        }

        public LocalDate date(String key) {
            return LocalDate.parse(s(key));
        }

        public Instant time(String key) {
            Object value = data.get(key);
            if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
            return null;
        }

        public boolean active() {
            return Boolean.TRUE.equals(data.get("is_active"));
        }
    }

    private static final ColumnMapRowMapper ROWS =
            new ColumnMapRowMapper() {
                @Override
                protected Object getColumnValue(ResultSet rs, int index) throws SQLException {
                    if (rs.getMetaData().getColumnType(index) == Types.TIMESTAMP)
                        return rs.getObject(index, LocalDateTime.class);
                    return super.getColumnValue(rs, index);
                }
            };

    public List<Row> rows(String sql, Object... args) {
        return jdbc.query(sql, ROWS, args).stream().map(Row::new).toList();
    }

    public Row one(String sql, Object... args) {
        return rows(sql, args).stream()
                .findFirst()
                .orElseThrow(() -> messages.error("ops.error.missing"));
    }

    public long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public BigDecimal sum(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long insert(String table, String columns, Object... values) {
        String sql =
                "INSERT INTO "
                        + table
                        + "("
                        + columns
                        + ") VALUES("
                        + String.join(",", Collections.nCopies(values.length, "?"))
                        + ")";
        var key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement = connection.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                    return statement;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    public void require(boolean condition, String key, Object... args) {
        if (!condition) throw new BusinessException(messages.text("ops.error." + key, args));
    }

    public String required(String value, int max) {
        String clean = optional(value, max);
        require(clean != null, "input");
        return clean;
    }

    public String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        require(clean.length() <= max, "input");
        return clean;
    }

    public String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T decode(String json, Class<T> type) {
        // H2 wraps JSON supplied through a JDBC string parameter in a JSON string.
        return mapper.readValue(
                json.startsWith("\"") ? mapper.readValue(json, String.class) : json, type);
    }

    public Row lockBooking(long id) {
        return one("SELECT * FROM bookings WHERE id=? FOR UPDATE", id);
    }

    public Row bookingRoom(long bookingId, long roomId) {
        return one("SELECT * FROM booking_rooms WHERE booking_id=? AND id=?", bookingId, roomId);
    }

    public Row settings() {
        return one("SELECT * FROM hotel_settings WHERE id=1");
    }

    public ZoneId zone() {
        return ZoneId.of(settings().s("timezone"));
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    public static LocalDateTime ts(Instant instant) {
        return instant == null
                ? null
                : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    public static String code(String prefix) {
        return prefix
                + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 24)
                        .toUpperCase(Locale.ROOT);
    }

    public static BigDecimal vnd(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal amount, BigDecimal rate) {
        return vnd(amount.multiply(rate).movePointLeft(2));
    }

    public static String like(String value) {
        return "%"
                + value.strip()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                + "%";
    }

    public Long actor() {
        return CmsActor.id();
    }

    public void audit(
            String action,
            String type,
            long id,
            Map<String, ?> before,
            Map<String, ?> after,
            String reason) {
        audit.record(action, type, id, before, after, reason);
    }

    public void event(String key, String type, long bookingId) {
        insert(
                "outbox_events",
                "event_key,event_type,aggregate_type,aggregate_id,payload,available_at",
                key,
                type,
                "BOOKING",
                bookingId,
                json(Map.of("bookingId", bookingId)),
                ts(Instant.now()));
        events.publishEvent(
                new com.example.hotelmanagement.modules.reception.GuestMessageService.BookingEvent(
                        bookingId, key, type));
        events.publishEvent(new ReceptionOutboxDispatcher.Wake());
    }
}
