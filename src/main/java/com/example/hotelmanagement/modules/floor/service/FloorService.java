package com.example.hotelmanagement.modules.floor.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.floor.dto.*;
import com.example.hotelmanagement.modules.floor.entity.Floor;
import com.example.hotelmanagement.modules.floor.repository.FloorRepository;
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
public class FloorService {
    private final FloorRepository repository;
    private final HotelInventoryCoordinator guard;
    private final CmsMessages messages;
    private final AuditLogService audit;

    @PreAuthorize("hasAnyAuthority('room.read','room.manage','room.block.manage')")
    public List<FloorResponse> list() {
        return repository
                .findAll(org.springframework.data.domain.Sort.by("sortOrder", "floorNumber", "id"))
                .stream()
                .map(this::view)
                .toList();
    }

    public FloorForm form(Long id) {
        var f = new FloorForm();
        BeanUtils.copyProperties(required(id), f);
        return f;
    }

    @Transactional
    public Long save(Long id, FloorForm f) {
        guard.lock();
        var e = id == null ? new Floor() : required(id);
        var before = id == null ? null : HotelSnapshots.of(view(e));
        String code = f.getCode().trim().toUpperCase(Locale.ROOT);
        if (id == null
                ? repository.existsByCodeIgnoreCase(code)
                : repository.existsByCodeIgnoreCaseAndIdNot(code, id))
            throw messages.error("hotel.error.duplicateCode");
        if (!f.isActive()
                && guard.count(
                                "SELECT COUNT(*) FROM rooms WHERE floor_id=? AND"
                                        + " status<>'INACTIVE'",
                                id)
                        > 0) throw messages.error("hotel.error.activeRooms");
        BeanUtils.copyProperties(f, e);
        e.setCode(code);
        e.setName(f.getName().trim());
        repository.saveAndFlush(e);
        audit.record("FLOOR_SAVE", "FLOOR", e.getId(), before, HotelSnapshots.of(view(e)), null);
        return e.getId();
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var e = required(id);
        if (guard.count("SELECT COUNT(*) FROM rooms WHERE floor_id=?", id) > 0)
            throw messages.error("hotel.error.inUse");
        var before = HotelSnapshots.of(view(e));
        repository.delete(e);
        repository.flush();
        audit.record("FLOOR_DELETE", "FLOOR", id, before, null, null);
    }

    private Floor required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private FloorResponse view(Floor e) {
        return new FloorResponse(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getAreaName(),
                e.getFloorNumber(),
                e.getSortOrder(),
                e.isActive(),
                guard.count("SELECT COUNT(*) FROM rooms WHERE floor_id=?", e.getId()));
    }
}
