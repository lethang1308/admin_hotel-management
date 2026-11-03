package com.example.hotelmanagement.modules.policy.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.policy.dto.*;
import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.policy.repository.SurchargePolicyRepository;
import com.example.hotelmanagement.modules.pricing.service.PricingSupport;

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
@PreAuthorize("hasAuthority('policy.manage')")
public class SurchargePolicyService {
    private final SurchargePolicyRepository repository;
    private final HotelInventoryCoordinator guard;
    private final PricingSupport support;
    private final CmsMessages messages;
    private final AuditLogService audit;

    public List<PolicyView<SurchargePolicyForm>> list() {
        return repository
                .findAll(
                        org.springframework.data.domain.Sort.by("code")
                                .and(
                                        org.springframework.data.domain.Sort.by(
                                                org.springframework.data.domain.Sort.Direction.DESC,
                                                "version")))
                .stream()
                .map(this::view)
                .toList();
    }

    public PolicyView<SurchargePolicyForm> get(Long id) {
        return view(required(id));
    }

    public SurchargePolicyForm form(Long id) {
        return form(required(id));
    }

    @Transactional
    public Long save(Long id, SurchargePolicyForm f) {
        guard.lock();
        f.setCode(support.code(f.getCode()));
        support.validate(f);
        SurchargePolicy old = id == null ? null : required(id);
        support.require(
                f.getToMinutes() == null
                        || (f.getFromMinutes() != null && f.getToMinutes() > f.getFromMinutes()),
                "price.error.minuteRange");
        if (f.getRoomTypeId() != null)
            support.roomTypes(
                    Set.of(f.getRoomTypeId()),
                    old == null || old.getRoomTypeId() == null
                            ? Set.of()
                            : Set.of(old.getRoomTypeId()),
                    false);
        String code = support.code(f.getCode());
        if (old == null)
            support.require(!repository.existsByCodeIgnoreCase(code), "price.error.code");
        else {
            support.require(old.getCode().equals(code), "price.error.immutableCode");
            support.latest("surcharge_policies", code, old.getVersion());
        }
        var before = old == null ? null : support.snapshot(form(old));
        boolean clone = old != null && referenced(id);
        SurchargePolicy r = old == null || clone ? new SurchargePolicy() : old;
        if (clone) old.setActive(false);
        BeanUtils.copyProperties(f, r);
        r.setCode(code);
        r.setName(f.getName().trim());
        if (old == null || clone) {
            r.setVersion(old == null ? 1 : support.latest("surcharge_policies", code) + 1);
        }
        repository.saveAndFlush(r);
        audit.record(
                clone ? "SURCHARGE_POLICY_VERSION_CREATE" : "SURCHARGE_POLICY_SAVE",
                "SURCHARGE_POLICY",
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
        if (!r.isActive()) support.latest("surcharge_policies", r.getCode(), r.getVersion());
        var before = support.snapshot(form(r));
        r.setActive(!r.isActive());
        audit.record(
                "SURCHARGE_POLICY_TOGGLE",
                "SURCHARGE_POLICY",
                id,
                before,
                support.snapshot(form(r)),
                null);
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var r = required(id);
        support.require(!referenced(id), "price.error.referenced");
        var before = support.snapshot(form(r));
        repository.delete(r);
        repository.flush();
        audit.record("SURCHARGE_POLICY_DELETE", "SURCHARGE_POLICY", id, before, null, null);
    }

    private boolean referenced(Long id) {
        return support.count("SELECT COUNT(*) FROM booking_charges WHERE surcharge_policy_id=?", id)
                > 0;
    }

    private SurchargePolicy required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private SurchargePolicyForm form(SurchargePolicy r) {
        var f = new SurchargePolicyForm();
        BeanUtils.copyProperties(r, f);
        return f;
    }

    private PolicyView<SurchargePolicyForm> view(SurchargePolicy r) {
        return new PolicyView<>(
                r.getId(),
                r.getVersion(),
                form(r),
                referenced(r.getId()),
                support.latest("surcharge_policies", r.getCode()) == r.getVersion(),
                false);
    }
}
