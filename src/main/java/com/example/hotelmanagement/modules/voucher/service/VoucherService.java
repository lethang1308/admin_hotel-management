package com.example.hotelmanagement.modules.voucher.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.pricing.service.PricingSupport;
import com.example.hotelmanagement.modules.voucher.dto.*;
import com.example.hotelmanagement.modules.voucher.entity.*;
import com.example.hotelmanagement.modules.voucher.repository.VoucherRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('voucher.manage')")
public class VoucherService {
    private final VoucherRepository repository;
    private final HotelInventoryCoordinator guard;
    private final PricingSupport support;
    private final CmsMessages messages;
    private final AuditLogService audit;

    public List<VoucherView> list(
            String search, Boolean active, VoucherDiscountType type, String validity) {
        Instant now = Instant.now();
        return repository
                .findAll(
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id"))
                .stream()
                .filter(
                        v ->
                                search == null
                                        || search.isBlank()
                                        || (v.getCode() + " " + v.getName())
                                                .toLowerCase(Locale.ROOT)
                                                .contains(search.trim().toLowerCase(Locale.ROOT)))
                .filter(v -> active == null || v.isActive() == active)
                .filter(v -> type == null || v.getDiscountType() == type)
                .filter(
                        v ->
                                validity == null
                                        || validity.isBlank()
                                        || switch (validity) {
                                            case "CURRENT" -> !now.isBefore(v.getStartsAt())
                                                    && now.isBefore(v.getExpiresAt());
                                            case "UPCOMING" -> now.isBefore(v.getStartsAt());
                                            case "EXPIRED" -> !now.isBefore(v.getExpiresAt());
                                            default -> false;
                                        })
                .map(this::view)
                .toList();
    }

    public VoucherView get(Long id) {
        return view(required(id));
    }

    public VoucherForm form(Long id) {
        return form(required(id));
    }

    @Transactional
    public Long save(Long id, VoucherForm f) {
        guard.lock();
        f.setCode(support.code(f.getCode()));
        support.validate(f);
        support.require(
                f.getDiscountValue().signum() > 0
                        && (f.getDiscountType() != VoucherDiscountType.PERCENT
                                || f.getDiscountValue().compareTo(new java.math.BigDecimal("100"))
                                        <= 0),
                "price.error.percent");
        support.require(
                f.getMaxDiscountAmount() == null || f.getMaxDiscountAmount().signum() > 0,
                "price.error.positive");
        support.require(
                f.getExpiresAt().isAfter(f.getStartsAt())
                        && (f.getStayUntil() == null
                                || (f.getStayFrom() != null
                                        && f.getStayUntil().isAfter(f.getStayFrom()))),
                "price.error.dates");
        Voucher r = id == null ? new Voucher() : required(id);
        String code = support.code(f.getCode());
        if (id == null)
            support.require(!repository.existsByCodeIgnoreCase(code), "price.error.code");
        else support.require(code.equals(r.getCode()), "price.error.immutableCode");
        support.roomTypes(f.getRoomTypeIds(), id == null ? null : r.getRoomTypeIds(), false);
        var before = id == null ? null : support.snapshot(form(r));
        if (id != null && used(id)) {
            VoucherForm old = form(r);
            support.require(
                    !f.getExpiresAt().isBefore(old.getExpiresAt()), "price.error.usedVoucher");
            support.require(
                    old.getMaxUses() == null
                            ? f.getMaxUses() == null
                            : (f.getMaxUses() == null || f.getMaxUses() >= old.getMaxUses()),
                    "price.error.usedVoucher");
            support.require(
                    f.getMaxUses() == null || f.getMaxUses() >= consumed(id), "price.error.quota");
            old.setExpiresAt(f.getExpiresAt());
            old.setMaxUses(f.getMaxUses());
            old.setActive(f.isActive());
            support.require(
                    old.getRoomTypeIds().equals(f.getRoomTypeIds()), "price.error.usedVoucher");
            old.setRoomTypeIds(f.getRoomTypeIds());
            support.require(
                    equivalent(support.snapshot(old), support.snapshot(f)),
                    "price.error.usedVoucher");
        }
        BeanUtils.copyProperties(f, r, "startsAt", "expiresAt");
        r.setCode(code);
        r.setName(f.getName().trim());
        r.setRoomTypeIds(new LinkedHashSet<>(f.getRoomTypeIds()));
        r.setStartsAt(f.getStartsAt().atZone(support.zone()).toInstant());
        r.setExpiresAt(f.getExpiresAt().atZone(support.zone()).toInstant());
        if (id == null) {
            support.require(CmsActor.id() != null, "hotel.error.actor");
            r.setCreatedBy(CmsActor.id());
            r.setCreatedAt(Instant.now());
        }
        repository.saveAndFlush(r);
        audit.record("VOUCHER_SAVE", "VOUCHER", r.getId(), before, support.snapshot(form(r)), null);
        return r.getId();
    }

    @Transactional
    public void toggle(Long id) {
        guard.lock();
        var r = required(id);
        var before = support.snapshot(form(r));
        r.setActive(!r.isActive());
        audit.record("VOUCHER_TOGGLE", "VOUCHER", id, before, support.snapshot(form(r)), null);
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var r = required(id);
        support.require(!used(id), "price.error.referenced");
        var before = support.snapshot(form(r));
        r.getRoomTypeIds().clear();
        repository.flush();
        repository.delete(r);
        repository.flush();
        audit.record("VOUCHER_DELETE", "VOUCHER", id, before, null, null);
    }

    private boolean equivalent(Map<String, Object> a, Map<String, Object> b) {
        for (String k : a.keySet()) {
            var x = a.get(k);
            var y = b.get(k);
            if (x instanceof java.math.BigDecimal p && y instanceof java.math.BigDecimal q) {
                if (p.compareTo(q) != 0) return false;
            } else if (!Objects.equals(x, y)) return false;
        }
        return true;
    }

    private boolean used(Long id) {
        return support.count("SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id=?", id) > 0;
    }

    private long consumed(Long id) {
        return support.count(
                "SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id=? AND status IN"
                        + " ('RESERVED','REDEEMED')",
                id);
    }

    private Voucher required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private VoucherForm form(Voucher r) {
        var f = new VoucherForm();
        BeanUtils.copyProperties(r, f, "startsAt", "expiresAt");
        f.setStartsAt(LocalDateTime.ofInstant(r.getStartsAt(), support.zone()));
        f.setExpiresAt(LocalDateTime.ofInstant(r.getExpiresAt(), support.zone()));
        f.setRoomTypeIds(new LinkedHashSet<>(r.getRoomTypeIds()));
        return f;
    }

    private VoucherView view(Voucher r) {
        long
                reserved =
                        support.count(
                                "SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id=? AND"
                                        + " status='RESERVED'",
                                r.getId()),
                redeemed =
                        support.count(
                                "SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id=? AND"
                                        + " status='REDEEMED'",
                                r.getId()),
                released =
                        support.count(
                                "SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id=? AND"
                                        + " status='RELEASED'",
                                r.getId());
        return new VoucherView(
                r.getId(),
                form(r),
                reserved + redeemed + released > 0,
                reserved,
                redeemed,
                released,
                r.getMaxUses() == null ? null : Math.max(0, r.getMaxUses() - reserved - redeemed));
    }
}
