package com.example.hotelmanagement.modules.room.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.*;
import com.example.hotelmanagement.modules.room.dto.*;
import com.example.hotelmanagement.modules.room.entity.*;
import com.example.hotelmanagement.modules.room.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('room.block.manage')")
public class RoomBlockService {
    private final RoomBlockRepository blocks;
    private final RoomRepository rooms;
    private final HotelInventoryCoordinator guard;
    private final RoomStatusWriter statuses;
    private final CmsMessages messages;
    private final AuditLogService audit;
    private final JdbcTemplate jdbc;

    @Transactional
    public Long create(RoomBlockForm f) {
        guard.lock();
        Room room =
                rooms.lockById(f.getRoomId())
                        .orElseThrow(() -> messages.error("hotel.error.notFound"));
        return createLocked(room, f);
    }

    // Used by status action after the same guard and room lock have been acquired.
    @Transactional
    public Long createLocked(Room room, RoomBlockForm f) {
        LocalDate today = guard.today();
        if (f.getFromDate() == null
                || f.getToDate() == null
                || f.getFromDate().isBefore(today)
                || !f.getToDate().isAfter(f.getFromDate())
                || f.getToDate().isAfter(guard.horizon()))
            throw messages.error("hotel.error.blockDates");
        if (room.getStatus() == RoomStatus.INACTIVE
                || f.getFromDate().isBefore(room.getSellFrom())
                || (room.getSellUntil() != null && f.getToDate().isAfter(room.getSellUntil())))
            throw messages.error("hotel.error.saleWindow");
        if ((!f.getFromDate().isAfter(today)
                        && (room.getStatus() == RoomStatus.OCCUPIED
                                || guard.openStay(room.getId())))
                || guard.count(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_id=? AND"
                                        + " stay_date>=? AND stay_date<?",
                                room.getId(),
                                f.getFromDate(),
                                f.getToDate())
                        > 0
                || guard.count(
                                "SELECT COUNT(*) FROM room_allocations WHERE room_id=? AND status"
                                    + " IN ('SCHEDULED','OCCUPIED') AND from_date<? AND to_date>?",
                                room.getId(),
                                f.getToDate(),
                                f.getFromDate())
                        > 0) throw messages.error("hotel.error.blockConflict");
        if (CmsActor.id() == null) throw messages.error("hotel.error.actor");
        RoomBlock block =
                blocks.saveAndFlush(
                        RoomBlock.builder()
                                .room(room)
                                .blockType(f.getBlockType())
                                .fromDate(f.getFromDate())
                                .toDate(f.getToDate())
                                .status(RoomBlockStatus.ACTIVE)
                                .reason(f.getReason().trim())
                                .createdBy(CmsActor.id())
                                .createdAt(Instant.now())
                                .build());
        List<Object[]> claims =
                f.getFromDate()
                        .datesUntil(f.getToDate())
                        .map(d -> new Object[] {room.getId(), d, block.getId()})
                        .toList();
        jdbc.batchUpdate(
                "INSERT INTO room_night_claims(room_id,stay_date,room_block_id) VALUES(?,?,?)",
                claims);
        if (!f.getFromDate().isAfter(today))
            statuses.change(room, RoomStatus.MAINTENANCE, f.getReason());
        guard.reconcile(List.of(room.getRoomType().getId()));
        audit.record(
                "ROOM_BLOCK_CREATE",
                "ROOM_BLOCK",
                block.getId(),
                null,
                snapshot(block),
                f.getReason());
        return block.getId();
    }

    @Transactional
    public void release(Long id, String reason) {
        guard.lock();
        RoomBlock block =
                blocks.lockById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
        Room room = rooms.lockById(block.getRoom().getId()).orElseThrow();
        if (block.getStatus() != RoomBlockStatus.ACTIVE)
            throw messages.error("hotel.error.blockReleased");
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw messages.error("hotel.error.reason");
        var before = snapshot(block);
        LocalDate today = guard.today();
        boolean current = !block.getFromDate().isAfter(today);
        block.setStatus(RoomBlockStatus.RELEASED);
        block.setReleasedBy(CmsActor.id());
        block.setReleasedAt(Instant.now());
        jdbc.update(
                "DELETE FROM room_night_claims WHERE room_block_id=? AND stay_date>=?", id, today);
        if (current
                && room.getStatus() == RoomStatus.MAINTENANCE
                && !guard.currentBlock(room.getId()))
            statuses.change(room, RoomStatus.CLEANING, reason);
        guard.reconcile(List.of(room.getRoomType().getId()));
        audit.record("ROOM_BLOCK_RELEASE", "ROOM_BLOCK", id, before, snapshot(block), reason);
    }

    private Map<String, Object> snapshot(RoomBlock b) {
        return Map.of(
                "roomId",
                b.getRoom().getId(),
                "fromDate",
                b.getFromDate(),
                "toDate",
                b.getToDate(),
                "status",
                b.getStatus().name(),
                "blockType",
                b.getBlockType().name(),
                "reason",
                b.getReason());
    }
}
