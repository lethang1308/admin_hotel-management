package com.example.hotelmanagement.modules.policy.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.policy.dto.*;
import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.policy.repository.CancellationPolicyRepository;
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
public class CancellationPolicyService {
    private final CancellationPolicyRepository repository;
    private final HotelInventoryCoordinator guard;
    private final PricingSupport support;
    private final CmsMessages messages;
    private final AuditLogService audit;

    public List<PolicyView<CancellationPolicyForm>> list() {
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

    public PolicyView<CancellationPolicyForm> get(Long id) {
        return view(required(id));
    }

    public CancellationPolicyForm form(Long id) {
        return form(required(id));
    }

    @Transactional
    public Long save(Long id, CancellationPolicyForm f) {
        guard.lock();
        f.setCode(support.code(f.getCode()));
        support.validate(f);
        CancellationPolicy old = id == null ? null : required(id);
        support.fee(f.getNoShowFeeType().name(), f.getNoShowFeeValue());
        Set<Integer> thresholds = new HashSet<>();
        for (var tier : f.getRules()) {
            support.require(thresholds.add(tier.getMinHoursBeforeCheckin()), "price.error.tiers");
            support.fee(tier.getFeeType().name(), tier.getFeeValue());
        }
        support.require(thresholds.contains(0), "price.error.zeroTier");
        String code = support.code(f.getCode());
        if (old == null)
            support.require(!repository.existsByCodeIgnoreCase(code), "price.error.code");
        else {
            support.require(old.getCode().equals(code), "price.error.immutableCode");
            support.latest("cancellation_policies", code, old.getVersion());
        }
        boolean defaultBefore =
                old != null && support.defaultPolicy("default_cancellation_policy_id", id);
        support.require(!defaultBefore || f.isActive(), "price.error.defaultActive");
        var before = old == null ? null : support.snapshot(form(old));
        boolean clone = old != null && referenced(id);
        CancellationPolicy r = old == null || clone ? new CancellationPolicy() : old;
        if (clone) old.setActive(false);
        BeanUtils.copyProperties(f, r, "rules");
        r.setCode(code);
        r.setName(f.getName().trim());
        if (old == null || clone) {
            r.setVersion(old == null ? 1 : support.latest("cancellation_policies", code) + 1);
            r.setCreatedAt(Instant.now());
        }
        if (!clone && old != null) {
            r.getRules().clear();
            repository.flush();
        }
        r.getRules().clear();
        for (var tier : f.getRules()) {
            var e = new CancellationPolicyRule();
            BeanUtils.copyProperties(tier, e);
            e.setPolicy(r);
            r.getRules().add(e);
        }
        repository.saveAndFlush(r);
        if (clone && defaultBefore) {
            support.setDefault("default_cancellation_policy_id", r.getId());
            audit.record(
                    "CANCELLATION_POLICY_DEFAULT",
                    "HOTEL",
                    1L,
                    Map.of("policyId", id),
                    Map.of("policyId", r.getId()),
                    null);
        }
        audit.record(
                clone ? "CANCELLATION_POLICY_VERSION_CREATE" : "CANCELLATION_POLICY_SAVE",
                "CANCELLATION_POLICY",
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
        if (!r.isActive()) support.latest("cancellation_policies", r.getCode(), r.getVersion());
        support.require(
                !r.isActive() || !support.defaultPolicy("default_cancellation_policy_id", id),
                "price.error.defaultActive");
        var before = support.snapshot(form(r));
        r.setActive(!r.isActive());
        audit.record(
                "CANCELLATION_POLICY_TOGGLE",
                "CANCELLATION_POLICY",
                id,
                before,
                support.snapshot(form(r)),
                null);
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var r = required(id);
        support.require(
                !referenced(id) && !support.defaultPolicy("default_cancellation_policy_id", id),
                "price.error.referenced");
        var before = support.snapshot(form(r));
        repository.delete(r);
        repository.flush();
        audit.record("CANCELLATION_POLICY_DELETE", "CANCELLATION_POLICY", id, before, null, null);
    }

    @Transactional
    public void makeDefault(Long id) {
        guard.lock();
        var r = required(id);
        support.require(r.isActive(), "price.error.defaultActive");
        support.latest("cancellation_policies", r.getCode(), r.getVersion());
        support.setDefault("default_cancellation_policy_id", id);
        audit.record(
                "CANCELLATION_POLICY_DEFAULT", "HOTEL", 1L, null, Map.of("policyId", id), null);
    }

    private boolean referenced(Long id) {
        return support.count("SELECT COUNT(*) FROM bookings WHERE cancellation_policy_id=?", id)
                > 0;
    }

    private CancellationPolicy required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private CancellationPolicyForm form(CancellationPolicy r) {
        var f = new CancellationPolicyForm();
        BeanUtils.copyProperties(r, f, "rules");
        f.setRules(
                r.getRules().stream()
                        .map(
                                t -> {
                                    var tier = new CancellationTierForm();
                                    BeanUtils.copyProperties(t, tier);
                                    return tier;
                                })
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        return f;
    }

    private PolicyView<CancellationPolicyForm> view(CancellationPolicy r) {
        return new PolicyView<>(
                r.getId(),
                r.getVersion(),
                form(r),
                referenced(r.getId()),
                support.latest("cancellation_policies", r.getCode()) == r.getVersion(),
                support.defaultPolicy("default_cancellation_policy_id", r.getId()));
    }
}
