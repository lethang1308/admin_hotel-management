package com.example.hotelmanagement.modules.room.service;

import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.room.entity.*;
import com.example.hotelmanagement.modules.room.repository.*;
import com.example.hotelmanagement.modules.roomtype.repository.RoomTypeRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Daily rollover; claims for previous nights are retained as history. */
@Service
@RequiredArgsConstructor
public class RoomCalendarService {
    private final HotelInventoryCoordinator guard;
    private final RoomRepository rooms;
    private final RoomBlockRepository blocks;
    private final RoomTypeRepository types;
    private final RoomStatusWriter statuses;
    private final JdbcTemplate jdbc;
    private final com.example.hotelmanagement.common.util.CmsMessages messages;
    private final com.example.hotelmanagement.modules.audit.service.AuditLogService audit;

    @Transactional
    public void synchronize() {
        guard.lock();
        var today = guard.today();
        var active =
                blocks.findAll().stream()
                        .filter(b -> b.getStatus() == RoomBlockStatus.ACTIVE)
                        .toList();
        for (var b : active) {
            Room r = rooms.lockById(b.getRoom().getId()).orElseThrow();
            if (!b.getToDate().isAfter(today)) {
                b.setStatus(RoomBlockStatus.RELEASED);
                audit.record(
                        "ROOM_BLOCK_EXPIRE",
                        "ROOM_BLOCK",
                        b.getId(),
                        Map.of("status", "ACTIVE"),
                        Map.of("status", "RELEASED"),
                        messages.text("hotel.reason.blockEnded"));
                b.setReleasedAt(Instant.now());
                if (r.getStatus() == RoomStatus.MAINTENANCE && !guard.currentBlock(r.getId()))
                    statuses.change(
                            r, RoomStatus.CLEANING, messages.text("hotel.reason.blockEnded"));
            } else if (!b.getFromDate().isAfter(today)
                    && r.getStatus() != RoomStatus.MAINTENANCE
                    && r.getStatus() != RoomStatus.OCCUPIED
                    && !guard.openStay(r.getId()))
                statuses.change(
                        r, RoomStatus.MAINTENANCE, messages.text("hotel.reason.blockStarted"));
        }
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM rooms WHERE sell_until<=? AND status NOT IN"
                                + " ('INACTIVE','OCCUPIED')",
                        Long.class,
                        today)) {
            Room r = rooms.lockById(id).orElseThrow();
            if (!guard.openStay(id))
                statuses.change(r, RoomStatus.INACTIVE, messages.text("hotel.reason.saleEnded"));
        }
        guard.reconcile(types.findAll().stream().map(t -> t.getId()).toList());
    }
}
