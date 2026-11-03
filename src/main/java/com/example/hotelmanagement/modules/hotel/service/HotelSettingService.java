package com.example.hotelmanagement.modules.hotel.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.dto.*;
import com.example.hotelmanagement.modules.hotel.entity.HotelSetting;
import com.example.hotelmanagement.modules.hotel.repository.HotelSettingRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HotelSettingService {
    private final HotelSettingRepository repository;
    private final HotelMediaStorage media;
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    private final CmsMessages messages;

    public record Brand(String name, String logoUrl) {}

    public record Policy(Long id, String name) {}

    public Brand brand() {
        return repository
                .findById(1L)
                .map(h -> new Brand(h.getName(), h.getLogoUrl()))
                .orElse(new Brand(null, null));
    }

    @PreAuthorize("hasAuthority('hotel.manage')")
    public HotelSettingResponse get() {
        return view(required());
    }

    @PreAuthorize("hasAuthority('hotel.manage')")
    public HotelSettingForm form() {
        var f = new HotelSettingForm();
        BeanUtils.copyProperties(required(), f);
        return f;
    }

    @PreAuthorize("hasAuthority('hotel.manage')")
    public List<Policy> deposits() {
        return jdbc.query(
                "SELECT id,name FROM deposit_policies WHERE is_active=true ORDER BY id",
                (r, n) -> new Policy(r.getLong(1), r.getString(2)));
    }

    @PreAuthorize("hasAuthority('hotel.manage')")
    public List<Policy> cancellations() {
        return jdbc.query(
                "SELECT id,name FROM cancellation_policies WHERE is_active=true ORDER BY id",
                (r, n) -> new Policy(r.getLong(1), r.getString(2)));
    }

    @Transactional
    @PreAuthorize("hasAuthority('hotel.manage')")
    public void update(HotelSettingForm f, MultipartFile logo) {
        HotelSetting h = repository.lockSingleton().orElseThrow();
        var before = view(h);
        if (jdbc.queryForObject(
                                "SELECT COUNT(*) FROM deposit_policies WHERE id=? AND"
                                        + " is_active=true",
                                Long.class,
                                f.getDefaultDepositPolicyId())
                        != 1
                || jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cancellation_policies WHERE id=? AND"
                                        + " is_active=true",
                                Long.class,
                                f.getDefaultCancellationPolicyId())
                        != 1) throw messages.error("hotel.error.policy");
        BeanUtils.copyProperties(f, h);
        h.setName(f.getName().trim());
        h.setAddress(f.getAddress().trim());
        String uploaded = media.store(logo);
        h.setLogoUrl(uploaded != null ? uploaded : media.validateUrl(f.getLogoUrl()));
        h.setUpdatedBy(CmsActor.id());
        h.setUpdatedAt(Instant.now());
        audit.record(
                "HOTEL_UPDATE",
                "HOTEL",
                1L,
                HotelSnapshots.of(before),
                HotelSnapshots.of(view(h)),
                null);
    }

    private HotelSetting required() {
        return repository
                .findById(1L)
                .orElseThrow(() -> messages.error("hotel.error.settingsMissing"));
    }

    private HotelSettingResponse view(HotelSetting h) {
        return new HotelSettingResponse(
                h.getId(),
                h.getName(),
                h.getDescription(),
                h.getAddress(),
                h.getHotline(),
                h.getEmail(),
                h.getLogoUrl(),
                h.getTimezone(),
                h.getCurrency(),
                h.getStandardCheckIn(),
                h.getStandardCheckOut(),
                h.getBookingHoldMinutes(),
                h.getDefaultTaxPercent(),
                h.getDefaultDepositPolicyId(),
                h.getDefaultCancellationPolicyId());
    }
}
