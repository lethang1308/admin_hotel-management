package com.example.hotelmanagement.modules.room.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.floor.repository.FloorRepository;
import com.example.hotelmanagement.modules.hotel.service.*;
import com.example.hotelmanagement.modules.room.dto.*;
import com.example.hotelmanagement.modules.room.entity.*;
import com.example.hotelmanagement.modules.room.repository.*;
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
@PreAuthorize("hasAuthority('room.manage')")
public class RoomService {
    private final RoomRepository rooms;
    private final FloorRepository floors;
    private final RoomTypeRepository types;
    private final HotelInventoryCoordinator guard;
    private final RoomStatusWriter statuses;
    private final RoomBlockService blocks;
    private final CmsMessages messages;
    private final AuditLogService audit;

    public RoomForm form(Long id) {
        Room r = required(id);
        var f = new RoomForm();
        BeanUtils.copyProperties(r, f);
        f.setRoomTypeId(r.getRoomType().getId());
        f.setFloorId(r.getFloor().getId());
        return f;
    }

    @Transactional
    public Long save(Long id, RoomForm f) {
        guard.lock();
        Room r =
                id == null
                        ? new Room()
                        : rooms.lockById(id)
                                .orElseThrow(() -> messages.error("hotel.error.notFound"));
        if (id != null) version(r, f.getVersion());
        String number = f.getRoomNumber().trim();
        if (id == null
                ? rooms.existsByRoomNumberIgnoreCase(number)
                : rooms.existsByRoomNumberIgnoreCaseAndIdNot(number, id))
            throw messages.error("hotel.error.duplicateRoom");
        if (f.getSellUntil() != null && !f.getSellUntil().isAfter(f.getSellFrom()))
            throw messages.error("hotel.error.saleDates");
        var type =
                types.findById(f.getRoomTypeId())
                        .orElseThrow(() -> messages.error("hotel.error.notFound"));
        var floor =
                floors.findById(f.getFloorId())
                        .orElseThrow(() -> messages.error("hotel.error.notFound"));
        if ((!type.isActive() && (id == null || !r.getRoomType().getId().equals(type.getId())))
                || (!floor.isActive()
                        && (id == null || !r.getFloor().getId().equals(floor.getId()))))
            throw messages.error("hotel.error.inactiveReference");
        Long oldType = id == null ? null : r.getRoomType().getId();
        var before = id == null ? null : snapshot(r);
        if (id != null) {
            if (!oldType.equals(type.getId())
                    && (r.getStatus() == RoomStatus.OCCUPIED
                            || guard.openStay(id)
                            || guard.futureAllocations(id)
                            || guard.futureBlocks(id)))
                throw messages.error("hotel.error.futureBooking");
            if ((!f.getSellFrom().equals(r.getSellFrom())
                            || !Objects.equals(f.getSellUntil(), r.getSellUntil()))
                    && (guard.count(
                                            "SELECT COUNT(*) FROM room_night_claims WHERE room_id=?"
                                                + " AND stay_date>=? AND (stay_date<? OR (? IS NOT"
                                                + " NULL AND stay_date>=?))",
                                            id,
                                            guard.today(),
                                            f.getSellFrom(),
                                            f.getSellUntil(),
                                            f.getSellUntil())
                                    > 0
                            || guard.count(
                                            "SELECT COUNT(*) FROM room_allocations WHERE room_id=?"
                                                + " AND status IN ('SCHEDULED','OCCUPIED') AND"
                                                + " to_date>? AND (from_date<? OR (? IS NOT NULL"
                                                + " AND to_date>?))",
                                            id,
                                            guard.today(),
                                            f.getSellFrom(),
                                            f.getSellUntil(),
                                            f.getSellUntil())
                                    > 0)) throw messages.error("hotel.error.futureBooking");
        }
        r.setRoomNumber(number);
        r.setRoomType(type);
        r.setFloor(floor);
        r.setSellFrom(f.getSellFrom());
        r.setSellUntil(f.getSellUntil());
        r.setNotes(f.getNotes());
        if (id == null) {
            rooms.saveAndFlush(rWithInitialStatus(r));
            statuses.created(r, messages.text("hotel.reason.created"));
        } else rooms.flush();
        // Initial status is written separately because rooms.status is NOT NULL.
        guard.reconcile(oldType == null ? List.of(type.getId()) : List.of(oldType, type.getId()));
        audit.record("ROOM_SAVE", "ROOM", r.getId(), before, snapshot(r), null);
        return r.getId();
    }

    private Room rWithInitialStatus(Room r) {
        r.setStatus(
                r.getSellUntil() != null && !r.getSellUntil().isAfter(guard.today())
                        ? RoomStatus.INACTIVE
                        : RoomStatus.AVAILABLE);
        return r;
    }

    @Transactional
    public void retire(Long id, String reason) {
        guard.lock();
        Room r = rooms.lockById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
        checkReason(reason);
        if (r.getStatus() == RoomStatus.OCCUPIED
                || guard.openStay(id)
                || guard.futureAllocations(id)
                || guard.futureBlocks(id)) throw messages.error("hotel.error.futureBooking");
        statuses.change(r, RoomStatus.INACTIVE, reason);
        guard.reconcile(List.of(r.getRoomType().getId()));
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        Room r = rooms.lockById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
        if (guard.count("SELECT COUNT(*) FROM room_allocations WHERE room_id=?", id) > 0
                || guard.count("SELECT COUNT(*) FROM room_blocks WHERE room_id=?", id) > 0
                || guard.count("SELECT COUNT(*) FROM maintenance_requests WHERE room_id=?", id) > 0
                || guard.count("SELECT COUNT(*) FROM housekeeping_tasks WHERE room_id=?", id) > 0)
            throw messages.error("hotel.error.roomUsed");
        var before = snapshot(r);
        Long type = r.getRoomType().getId();
        jdbc.update("DELETE FROM room_status_history WHERE room_id=?", id);
        rooms.delete(r);
        rooms.flush();
        guard.reconcile(List.of(type));
        audit.record("ROOM_DELETE", "ROOM", id, before, null, null);
    }

    private final JdbcTemplate jdbc;

    @PreAuthorize("hasAuthority('room.status.update')")
    @Transactional
    public void changeStatus(Long id, RoomStatusChangeForm f) {
        guard.lock();
        Room r = rooms.lockById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
        version(r, f.getVersion());
        checkReason(f.getReason());
        RoomStatus next = f.getStatus();
        if (next == r.getStatus()) return;
        if (r.getStatus() == RoomStatus.INACTIVE
                && org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication()
                        .getAuthorities()
                        .stream()
                        .noneMatch(a -> a.getAuthority().equals("room.manage")))
            throw new org.springframework.security.access.AccessDeniedException(
                    "Room reactivation requires room.manage");
        if (next == RoomStatus.MAINTENANCE) {
            var block = new RoomBlockForm();
            block.setRoomId(id);
            block.setFromDate(guard.today());
            block.setToDate(f.getMaintenanceUntil());
            block.setReason(f.getReason());
            blocks.createLocked(r, block);
            return;
        }
        if (next == RoomStatus.INACTIVE) throw messages.error("hotel.error.useRetire");
        if (next == RoomStatus.AVAILABLE && guard.unverifiedCleaning(id))
            throw messages.error("hotel.error.cleaningInspection");
        if (guard.currentBlock(id) || guard.openStay(id))
            throw messages.error("hotel.error.roomBusy");
        boolean valid =
                switch (r.getStatus()) {
                    case AVAILABLE -> Set.of(
                                    RoomStatus.OCCUPIED, RoomStatus.DIRTY, RoomStatus.CLEANING)
                            .contains(next);
                    case OCCUPIED -> next == RoomStatus.DIRTY;
                    case DIRTY -> next == RoomStatus.CLEANING;
                    case CLEANING -> next == RoomStatus.AVAILABLE || next == RoomStatus.DIRTY;
                    case MAINTENANCE -> false;
                    case INACTIVE -> next == RoomStatus.CLEANING;
                };
        if (!valid) throw messages.error("hotel.error.transition");
        if (next == RoomStatus.OCCUPIED
                && (r.getSellFrom().isAfter(guard.today())
                        || (r.getSellUntil() != null && !r.getSellUntil().isAfter(guard.today()))
                        || guard.futureAllocations(id)
                        || guard.futureBlocks(id))) throw messages.error("hotel.error.roomBusy");
        if (r.getStatus() == RoomStatus.INACTIVE
                && (!r.getRoomType().isActive()
                        || !r.getFloor().isActive()
                        || (r.getSellUntil() != null && !r.getSellUntil().isAfter(guard.today()))))
            throw messages.error("hotel.error.inactiveReference");
        statuses.change(r, next, f.getReason());
        guard.reconcile(List.of(r.getRoomType().getId()));
    }

    private void version(Room r, Integer expected) {
        if (expected == null || expected != r.getVersion())
            throw messages.error("hotel.error.stale");
    }

    private void checkReason(String s) {
        if (s == null || s.isBlank() || s.length() > 500)
            throw messages.error("hotel.error.reason");
    }

    private Room required(Long id) {
        return rooms.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private Map<String, Object> snapshot(Room r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("roomNumber", r.getRoomNumber());
        s.put("roomTypeId", r.getRoomType().getId());
        s.put("floorId", r.getFloor().getId());
        s.put("status", r.getStatus());
        s.put("sellFrom", r.getSellFrom());
        s.put("sellUntil", r.getSellUntil());
        s.put("notes", r.getNotes());
        return s;
    }
}
