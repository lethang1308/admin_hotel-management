package com.example.hotelmanagement.modules.room.service;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.room.dto.RoomBlockForm;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
@PreAuthorize("hasAuthority('maintenance.manage')")
public class RoomMaintenanceService {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;
    private final RoomBlockService blocks;

    public record Fault(
            String key,
            long room,
            String title,
            String description,
            String priority,
            boolean block,
            LocalDate from,
            LocalDate until,
            String reason) {}

    public long create(Fault f) {
        inventory.lock();
        db.require(f.key() != null && f.key().matches("[A-F0-9]{24}"), "input");
        var r = db.one("SELECT * FROM rooms WHERE id=? FOR UPDATE", f.room());
        String title = db.required(f.title(), 200),
                description = db.optional(f.description(), 5000);
        db.require(
                Set.of("LOW", "NORMAL", "HIGH", "URGENT")
                        .contains(Objects.toString(f.priority(), "")),
                "input");
        var old = db.rows("SELECT * FROM maintenance_requests WHERE request_key=?", f.key());
        if (!old.isEmpty()) {
            var x = old.get(0);
            db.require(
                    x.id("room_id") == f.room()
                            && Objects.equals(x.id("reported_by"), db.actor())
                            && x.s("title").equals(title)
                            && Objects.equals(x.s("description"), description)
                            && x.s("priority").equals(f.priority()),
                    "input");
            var linked =
                    db.rows("SELECT * FROM room_blocks WHERE maintenance_request_id=?", x.id("id"));
            db.require(f.block() == !linked.isEmpty(), "input");
            if (f.block())
                db.require(
                        Objects.equals(linked.get(0).date("from_date"), f.from())
                                && Objects.equals(linked.get(0).date("to_date"), f.until())
                                && Objects.equals(
                                        linked.get(0).s("reason"), db.required(f.reason(), 500)),
                        "input");
            return x.id("id");
        }
        db.require(!r.s("status").equals("INACTIVE"), "state");
        long id =
                db.insert(
                        "maintenance_requests",
                        "room_id,reported_by,title,description,priority,request_key",
                        f.room(),
                        db.actor(),
                        title,
                        description,
                        f.priority(),
                        f.key());
        if (f.block()) {
            var form = new RoomBlockForm();
            form.setRoomId(f.room());
            form.setFromDate(f.from());
            form.setToDate(f.until());
            form.setReason(db.required(f.reason(), 500));
            blocks.createMaintenance(id, form);
        }
        db.audit(
                "MAINTENANCE_CREATE",
                "ROOM",
                f.room(),
                Map.of(),
                Map.of("request", id, "priority", f.priority(), "blocked", f.block()),
                title);
        return id;
    }

    public void update(long id, String action, String resolution) {
        inventory.lock();
        var initial = db.one("SELECT room_id FROM maintenance_requests WHERE id=?", id);
        long room = initial.id("room_id");
        db.one("SELECT id FROM rooms WHERE id=? FOR UPDATE", room);
        var t = db.one("SELECT * FROM maintenance_requests WHERE id=? FOR UPDATE", id);
        resolution = db.required(resolution, 500);
        if (action.equals("start")) {
            db.require(t.s("status").equals("OPEN"), "state");
            db.update(
                    "UPDATE maintenance_requests SET"
                        + " status='IN_PROGRESS',started_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    id);
        } else {
            db.require(Set.of("resolve", "cancel").contains(action), "input");
            String next = action.equals("resolve") ? "RESOLVED" : "CANCELLED";
            if (t.s("status").equals(next)) return;
            db.require(Set.of("OPEN", "IN_PROGRESS").contains(t.s("status")), "state");
            var linked =
                    db.rows(
                            "SELECT id,from_date,status FROM room_blocks WHERE"
                                + " maintenance_request_id=? AND status IN ('ACTIVE','RELEASED')"
                                + " ORDER BY id",
                            id);
            boolean current =
                    linked.stream().anyMatch(b -> !b.date("from_date").isAfter(db.today()));
            for (var b : linked)
                if (b.s("status").equals("ACTIVE"))
                    blocks.releaseMaintenance(id, b.id("id"), resolution);
            db.update(
                    "UPDATE maintenance_requests SET"
                        + " status=?,resolution=?,resolved_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    next,
                    resolution,
                    id);
            // A future block release must not dirty an occupied room today.
            if (current && !inventory.currentBlock(room) && !inventory.openStay(room)) {
                var active =
                        db.rows(
                                "SELECT id FROM housekeeping_tasks WHERE room_id=? AND status IN"
                                    + " ('PENDING','IN_PROGRESS','COMPLETED')",
                                room);
                if (active.isEmpty())
                    db.insert(
                            "housekeeping_tasks",
                            "room_id,created_by,status,started_at,notes",
                            room,
                            db.actor(),
                            "IN_PROGRESS",
                            ts(Instant.now()),
                            "Vệ sinh sau bảo trì / Post-maintenance cleaning · " + resolution);
            }
        }
        db.audit(
                "MAINTENANCE_" + action.toUpperCase(Locale.ROOT),
                "ROOM",
                room,
                Map.of("status", t.s("status")),
                Map.of("request", id),
                resolution);
    }

    @Transactional(readOnly = true)
    public List<Row> list(String status) {
        db.require(
                status == null
                        || status.isBlank()
                        || Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CANCELLED").contains(status),
                "input");
        String where =
                status == null || status.isBlank()
                        ? "m.status IN ('OPEN','IN_PROGRESS')"
                        : "m.status=?";
        return db.rows(
                "SELECT m.*,r.room_number,u.full_name AS reporter,b.id AS"
                    + " block_id,b.from_date,b.to_date,b.status AS block_status FROM"
                    + " maintenance_requests m JOIN rooms r ON r.id=m.room_id JOIN users u ON"
                    + " u.id=m.reported_by LEFT JOIN room_blocks b ON b.maintenance_request_id=m.id"
                    + " WHERE "
                        + where
                        + " ORDER BY CASE m.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN"
                        + " 'NORMAL' THEN 2 ELSE 3 END,m.created_at DESC LIMIT 200",
                status == null || status.isBlank() ? new Object[] {} : new Object[] {status});
    }
}
