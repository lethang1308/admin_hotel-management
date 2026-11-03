package com.example.hotelmanagement.modules.room.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.*;
import com.example.hotelmanagement.modules.room.dto.*;
import com.example.hotelmanagement.modules.room.entity.*;
import com.example.hotelmanagement.modules.room.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** Internal writer: callers must hold the singleton inventory lock inside their transaction. */
@Service
@RequiredArgsConstructor
public class RoomStatusWriter {
    private final RoomStatusHistoryRepository history;
    private final AuditLogService audit;

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void created(Room room, String reason) {
        history.save(
                RoomStatusHistory.builder()
                        .room(room)
                        .newStatus(room.getStatus())
                        .changedBy(CmsActor.id())
                        .reason(reason)
                        .changedAt(Instant.now())
                        .build());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void change(Room room, RoomStatus next, String reason) {
        RoomStatus old = room.getStatus();
        if (old == next) return;
        room.setStatus(next);
        history.save(
                RoomStatusHistory.builder()
                        .room(room)
                        .oldStatus(old)
                        .newStatus(next)
                        .changedBy(CmsActor.id())
                        .reason(reason)
                        .changedAt(Instant.now())
                        .build());
        audit.record(
                "ROOM_STATUS_CHANGE",
                "ROOM",
                room.getId(),
                old == null ? null : Map.of("status", old.name()),
                Map.of("status", next.name()),
                reason);
    }
}
