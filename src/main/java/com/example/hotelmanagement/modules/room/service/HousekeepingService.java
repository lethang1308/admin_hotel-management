package com.example.hotelmanagement.modules.room.service;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HousekeepingService {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;

    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('room.status.update')")
    public long request(long room, String notes, boolean priority) {
        inventory.lock();
        var r = db.one("SELECT * FROM rooms WHERE id=? FOR UPDATE", room);
        db.require(
                !inventory.currentBlock(room)
                        && !inventory.unresolvedMaintenance(room)
                        && !Set.of("INACTIVE", "MAINTENANCE").contains(r.s("status")),
                "notReady");
        String kind = inventory.openStay(room) ? "STAY_OVER" : "TURNOVER";
        db.require(!kind.equals("STAY_OVER") || r.s("status").equals("OCCUPIED"), "notReady");
        notes = db.required(notes, 500);
        var existing =
                db.rows(
                        "SELECT * FROM housekeeping_tasks WHERE room_id=? AND status IN"
                            + " ('PENDING','IN_PROGRESS','COMPLETED') ORDER BY id",
                        room);
        if (!existing.isEmpty()) {
            var t = existing.get(0);
            db.require(t.s("task_kind").equals(kind), "state");
            if (priority)
                db.update("UPDATE housekeeping_tasks SET priority='HIGH' WHERE id=?", t.id("id"));
            db.audit(
                    "HOUSEKEEPING_REQUEST_REUSED",
                    "ROOM",
                    room,
                    Map.of(),
                    Map.of("task", t.id("id"), "priority", priority),
                    notes);
            return t.id("id");
        }
        long task =
                db.insert(
                        "housekeeping_tasks",
                        "room_id,created_by,task_kind,priority,notes",
                        room,
                        db.actor(),
                        kind,
                        priority ? "HIGH" : "NORMAL",
                        notes);
        if (kind.equals("TURNOVER")) status(r, "DIRTY", notes);
        db.audit(
                "HOUSEKEEPING_REQUEST",
                "ROOM",
                room,
                Map.of(),
                Map.of("task", task, "kind", kind),
                notes);
        return task;
    }

    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('room.status.update')")
    public void clean(long task, String action, String reason) {
        inventory.lock();
        var initial = db.one("SELECT room_id FROM housekeeping_tasks WHERE id=?", task);
        long room = initial.id("room_id");
        var r = db.one("SELECT * FROM rooms WHERE id=? FOR UPDATE", room);
        var t = db.one("SELECT * FROM housekeeping_tasks WHERE id=? FOR UPDATE", task);
        reason = db.required(reason, 500);
        boolean stay = t.s("task_kind").equals("STAY_OVER");
        boolean occupied = inventory.openStay(room);
        db.require(
                !inventory.currentBlock(room)
                        && !inventory.unresolvedMaintenance(room)
                        && (stay
                                ? occupied && r.s("status").equals("OCCUPIED")
                                : !occupied
                                        && Set.of("DIRTY", "CLEANING", "AVAILABLE")
                                                .contains(r.s("status"))),
                "notReady");
        String next;
        switch (action) {
            case "start" -> {
                db.require(t.s("status").equals("PENDING"), "state");
                next = "IN_PROGRESS";
                Long employee =
                        db
                                .rows(
                                        "SELECT id FROM employees WHERE user_id=? AND ended_on IS"
                                            + " NULL",
                                        db.actor())
                                .stream()
                                .map(x -> x.id("id"))
                                .findFirst()
                                .orElse(null);
                db.update(
                        "UPDATE housekeeping_tasks SET"
                            + " started_at=CURRENT_TIMESTAMP(6),assigned_employee_id=? WHERE id=?",
                        employee,
                        task);
            }
            case "complete" -> {
                db.require(t.s("status").equals("IN_PROGRESS"), "state");
                next = "COMPLETED";
                db.update(
                        "UPDATE housekeeping_tasks SET completed_at=CURRENT_TIMESTAMP(6) WHERE"
                            + " id=?",
                        task);
            }
            case "verify" -> {
                db.require(t.s("status").equals("COMPLETED"), "state");
                next = "VERIFIED";
                db.update(
                        "UPDATE housekeeping_tasks SET"
                            + " verified_by=?,verified_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                        db.actor(),
                        task);
            }
            default -> {
                db.require(false, "input");
                return;
            }
        }
        db.update("UPDATE housekeeping_tasks SET status=? WHERE id=?", next, task);
        if (!stay)
            status(
                    r,
                    next.equals("VERIFIED") && !inventory.unverifiedCleaning(room)
                            ? "AVAILABLE"
                            : "CLEANING",
                    reason);
        db.audit(
                "HOUSEKEEPING_" + next,
                "ROOM",
                room,
                Map.of("taskStatus", t.s("status")),
                Map.of("task", task, "kind", t.s("task_kind")),
                reason);
    }

    private void status(Row r, String next, String reason) {
        if (r.s("status").equals(next)) return;
        db.update(
                "UPDATE rooms SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " id=?",
                next,
                r.id("id"));
        db.insert(
                "room_status_history",
                "room_id,old_status,new_status,changed_by,reason",
                r.id("id"),
                r.s("status"),
                next,
                db.actor(),
                reason);
    }

    @PreAuthorize("hasAuthority('housekeeping.manage')")
    @Transactional(readOnly = true)
    public List<Row> queue(String status) {
        db.require(
                status == null
                        || status.isBlank()
                        || Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "VERIFIED", "CANCELLED")
                                .contains(status),
                "input");
        String filter =
                status == null || status.isBlank()
                        ? "h.status IN ('PENDING','IN_PROGRESS','COMPLETED')"
                        : "h.status=?";
        return db.rows(
                "SELECT h.*,r.room_number,r.status AS room_status,u.full_name AS cleaner FROM"
                    + " housekeeping_tasks h JOIN rooms r ON r.id=h.room_id LEFT JOIN employees e"
                    + " ON e.id=h.assigned_employee_id LEFT JOIN users u ON u.id=e.user_id WHERE "
                        + filter
                        + " ORDER BY CASE WHEN h.priority='HIGH' THEN 0 ELSE 1"
                        + " END,h.created_at,h.id LIMIT 200",
                status == null || status.isBlank() ? new Object[] {} : new Object[] {status});
    }
}
