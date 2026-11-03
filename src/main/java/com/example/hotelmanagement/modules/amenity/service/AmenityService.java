package com.example.hotelmanagement.modules.amenity.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.amenity.dto.*;
import com.example.hotelmanagement.modules.amenity.entity.Amenity;
import com.example.hotelmanagement.modules.amenity.repository.AmenityRepository;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('room.manage')")
public class AmenityService {
    private final AmenityRepository repository;
    private final HotelInventoryCoordinator guard;
    private final CmsMessages messages;
    private final AuditLogService audit;

    @PreAuthorize("hasAnyAuthority('room.read','room.manage','room.block.manage')")
    public List<AmenityResponse> list() {
        return repository.findAll(org.springframework.data.domain.Sort.by("name")).stream()
                .map(this::view)
                .toList();
    }

    public AmenityForm form(Long id) {
        var f = new AmenityForm();
        BeanUtils.copyProperties(required(id), f);
        return f;
    }

    @Transactional
    public Long save(Long id, AmenityForm f) {
        guard.lock();
        var e = id == null ? new Amenity() : required(id);
        var before = id == null ? null : HotelSnapshots.of(view(e));
        String code = f.getCode().trim().toUpperCase(Locale.ROOT);
        if (id == null
                ? repository.existsByCodeIgnoreCase(code)
                : repository.existsByCodeIgnoreCaseAndIdNot(code, id))
            throw messages.error("hotel.error.duplicateCode");

        BeanUtils.copyProperties(f, e);
        e.setCode(code);
        e.setName(f.getName().trim());
        repository.saveAndFlush(e);
        audit.record(
                "AMENITY_SAVE", "AMENITY", e.getId(), before, HotelSnapshots.of(view(e)), null);
        return e.getId();
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var e = required(id);
        if (guard.count("SELECT COUNT(*) FROM room_type_amenities WHERE amenity_id=?", id) > 0)
            throw messages.error("hotel.error.inUse");
        var before = HotelSnapshots.of(view(e));
        repository.delete(e);
        repository.flush();
        audit.record("AMENITY_DELETE", "AMENITY", id, before, null, null);
    }

    private Amenity required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private AmenityResponse view(Amenity e) {
        return new AmenityResponse(e.getId(), e.getCode(), e.getName(), e.getIcon(), e.isActive());
    }
}
