package com.example.hotelmanagement.modules.pricing.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.hotel.repository.HotelSettingRepository;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class PricingSupport {
    private final JdbcTemplate jdbc;
    private final CmsMessages messages;
    private final Validator validator;
    private final HotelSettingRepository hotels;

    public void validate(Object form) {
        if (!validator.validate(form).isEmpty()) throw messages.error("price.error.form");
    }

    public void require(boolean condition, String key) {
        if (!condition) throw messages.error(key);
    }

    public long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public String code(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    // Table names are constants supplied by our services, never request values.
    public int latest(String table, String code) {
        return jdbc.queryForObject(
                "SELECT MAX(version) FROM " + table + " WHERE code=?", Integer.class, code);
    }

    public void latest(String table, String code, int version) {
        require(latest(table, code) == version, "price.error.oldVersion");
    }

    public void roomTypes(Set<Long> ids, Set<Long> previous, boolean required) {
        require(ids != null && (!required || !ids.isEmpty()), "price.error.types");
        for (Long id : ids)
            require(
                    id != null
                            && count(
                                            "SELECT COUNT(*) FROM room_types WHERE id=? AND"
                                                    + " (is_active=true OR ?=true)",
                                            id,
                                            previous != null && previous.contains(id))
                                    == 1,
                    "price.error.types");
    }

    public ZoneId zone() {
        return ZoneId.of(
                jdbc.queryForObject(
                        "SELECT timezone FROM hotel_settings WHERE id=1", String.class));
    }

    public boolean defaultPolicy(String column, Long id) {
        return id != null
                && count("SELECT COUNT(*) FROM hotel_settings WHERE id=1 AND " + column + "=?", id)
                        > 0;
    }

    public void setDefault(String column, Long id) {
        var h = hotels.findById(1L).orElseThrow();
        if (column.equals("default_deposit_policy_id")) h.setDefaultDepositPolicyId(id);
        else h.setDefaultCancellationPolicyId(id);
        h.setUpdatedBy(CmsActor.id());
        h.setUpdatedAt(Instant.now());
    }

    public void fee(String kind, BigDecimal value) {
        require(
                value != null
                        && value.signum() >= 0
                        && (!kind.equals("PERCENT") || value.compareTo(new BigDecimal("100")) <= 0)
                        && (!Set.of("NONE", "FIRST_NIGHT").contains(kind) || value.signum() == 0),
                "price.error.fee");
    }

    public Map<String, Object> snapshot(Object form) {
        Map<String, Object> m = new LinkedHashMap<>();
        var bean = new BeanWrapperImpl(form);
        for (var d : bean.getPropertyDescriptors())
            if (!d.getName().equals("class") && bean.isReadableProperty(d.getName()))
                m.put(d.getName(), value(bean.getPropertyValue(d.getName())));
        return m;
    }

    private Object value(Object v) {
        if (v == null
                || v instanceof String
                || v instanceof Number
                || v instanceof Boolean
                || v instanceof Enum<?>
                || v instanceof java.time.temporal.TemporalAccessor) return v;
        if (v instanceof Collection<?> c) return c.stream().map(this::value).toList();
        return snapshot(v);
    }
}
