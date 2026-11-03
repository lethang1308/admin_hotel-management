package com.example.hotelmanagement.modules.pricing.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.pricing.dto.*;
import com.example.hotelmanagement.modules.pricing.entity.*;
import com.example.hotelmanagement.modules.pricing.repository.RateRuleRepository;
import com.example.hotelmanagement.modules.roomtype.repository.RoomTypeRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('pricing.manage')")
public class RateRuleService {
    private final RateRuleRepository repository;
    private final RoomTypeRepository types;
    private final JdbcTemplate jdbc;
    private final HotelInventoryCoordinator guard;
    private final PricingSupport support;
    private final CmsMessages messages;
    private final AuditLogService audit;

    @PreAuthorize("hasAnyAuthority('pricing.manage','room.read')")
    public List<RateRuleView> list(String search, RateRuleKind kind, Boolean active) {
        return repository
                .findAll(
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC,
                                "priority",
                                "id"))
                .stream()
                .filter(
                        r ->
                                search == null
                                        || search.isBlank()
                                        || (r.getCode() + " " + r.getName())
                                                .toLowerCase(Locale.ROOT)
                                                .contains(search.trim().toLowerCase(Locale.ROOT)))
                .filter(r -> kind == null || r.getKind() == kind)
                .filter(r -> active == null || r.isActive() == active)
                .map(this::view)
                .toList();
    }

    public RateRuleView get(Long id) {
        return view(required(id));
    }

    public RateRuleForm form(Long id) {
        return form(required(id));
    }

    @Transactional
    public Long save(Long id, RateRuleForm f) {
        guard.lock();
        f.setCode(support.code(f.getCode()));
        support.validate(f);
        support.require(
                f.getEndDate().isAfter(f.getStartDate())
                        && (f.getMaxNights() == null || f.getMaxNights() >= f.getMinNights()),
                "price.error.dates");
        support.require(
                f.getWeekdays() != null && !f.getWeekdays().isEmpty(), "price.error.weekdays");
        support.require(
                f.getKind() != RateRuleKind.NIGHT_OVERRIDE
                        || f.getValueType() == RateValueType.FIXED,
                "price.error.override");
        support.require(
                f.getValueType() != RateValueType.PERCENT
                        || f.getValue().compareTo(new java.math.BigDecimal("100")) <= 0,
                "price.error.percent");
        support.require(
                f.getKind() != RateRuleKind.LENGTH_DISCOUNT || f.getValue().signum() > 0,
                "price.error.positive");
        RateRule old = id == null ? null : required(id);
        String code = support.code(f.getCode());
        if (old == null)
            support.require(!repository.existsByCodeIgnoreCase(code), "price.error.code");
        else {
            support.require(old.getCode().equals(code), "price.error.immutableCode");
            support.latest("rate_rules", code, old.getVersion());
        }
        support.roomTypes(f.getRoomTypeIds(), old == null ? null : old.getRoomTypeIds(), true);
        var before = old == null ? null : support.snapshot(form(old));
        boolean clone = old != null && referenced(id);
        RateRule r = clone || old == null ? new RateRule() : old;
        if (clone) old.setActive(false);
        BeanUtils.copyProperties(f, r);
        r.setCode(code);
        r.setName(f.getName().trim());
        r.setWeekdayMask(
                f.getWeekdays().stream().mapToInt(d -> 1 << (d - 1)).reduce(0, (a, c) -> a | c));
        r.setRoomTypeIds(new LinkedHashSet<>(f.getRoomTypeIds()));
        if (old == null || clone) {
            r.setVersion(old == null ? 1 : support.latest("rate_rules", code) + 1);
            r.setCreatedAt(Instant.now());
        }
        repository.saveAndFlush(r);
        audit.record(
                clone ? "RATE_RULE_VERSION_CREATE" : "RATE_RULE_SAVE",
                "RATE_RULE",
                r.getId(),
                before,
                support.snapshot(form(r)),
                null);
        return r.getId();
    }

    @Transactional
    public void toggle(Long id) {
        guard.lock();
        var r = required(id);
        if (!r.isActive()) support.latest("rate_rules", r.getCode(), r.getVersion());
        var before = support.snapshot(form(r));
        r.setActive(!r.isActive());
        audit.record("RATE_RULE_TOGGLE", "RATE_RULE", id, before, support.snapshot(form(r)), null);
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var r = required(id);
        support.require(!referenced(id), "price.error.referenced");
        var before = support.snapshot(form(r));
        r.getRoomTypeIds().clear();
        repository.flush();
        repository.delete(r);
        repository.flush();
        audit.record("RATE_RULE_DELETE", "RATE_RULE", id, before, null, null);
    }

    @Transactional
    @PreAuthorize("hasAnyAuthority('pricing.manage','room.manage')")
    public void basePrice(BasePriceForm f) {
        guard.lock();
        support.validate(f);
        var t =
                types.findById(f.getRoomTypeId())
                        .orElseThrow(() -> messages.error("hotel.error.notFound"));
        var before = Map.of("basePrice", t.getBasePrice());
        t.setBasePrice(f.getBasePrice());
        audit.record(
                "ROOM_TYPE_BASE_PRICE_UPDATE",
                "ROOM_TYPE",
                t.getId(),
                before,
                Map.of("basePrice", t.getBasePrice()),
                null);
    }

    private boolean referenced(Long id) {
        return support.count(
                        "SELECT COUNT(*) FROM booking_room_nights WHERE rate_rule_id=? OR"
                                + " length_discount_rule_id=?",
                        id,
                        id)
                > 0;
    }

    private RateRule required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private RateRuleForm form(RateRule r) {
        var f = new RateRuleForm();
        BeanUtils.copyProperties(r, f);
        f.setRoomTypeIds(new LinkedHashSet<>(r.getRoomTypeIds()));
        f.setWeekdays(new LinkedHashSet<>());
        for (int day = 1; day <= 7; day++)
            if ((r.getWeekdayMask() & (1 << (day - 1))) != 0) f.getWeekdays().add(day);
        return f;
    }

    private RateRuleView view(RateRule r) {
        return new RateRuleView(
                r.getId(),
                r.getVersion(),
                form(r),
                referenced(r.getId()),
                support.latest("rate_rules", r.getCode()) == r.getVersion(),
                types.findAllById(r.getRoomTypeIds()).stream().map(t -> t.getName()).toList());
    }
}
