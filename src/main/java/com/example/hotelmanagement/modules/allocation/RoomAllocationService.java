package com.example.hotelmanagement.modules.allocation;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RoomAllocationService {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;

    public record Window(long type, LocalDate from, LocalDate to) {}

    public record Slot(long type, LocalDate date) implements Comparable<Slot> {
        @Override
        public int compareTo(Slot other) {
            int types = Long.compare(type, other.type);
            return types == 0 ? date.compareTo(other.date) : types;
        }
    }

    public void lockInventory(Collection<Window> windows) {
        var missing = new TreeSet<Long>();
        var slots = new TreeSet<Slot>();
        for (var window : windows) {
            window.from()
                    .datesUntil(window.to())
                    .forEach(date -> slots.add(new Slot(window.type(), date)));
        }
        for (var slot : slots) {
            if (db.count(
                            "SELECT COUNT(*) FROM room_type_inventory WHERE room_type_id=? AND"
                                    + " stay_date=?",
                            slot.type(),
                            slot.date())
                    == 0) missing.add(slot.type());
        }
        if (!missing.isEmpty()) inventory.reconcile(missing);
        for (var slot : slots) {
            db.one(
                    "SELECT * FROM room_type_inventory WHERE room_type_id=? AND stay_date=? FOR"
                            + " UPDATE",
                    slot.type(),
                    slot.date());
        }
    }

    public record Candidate(
            long id,
            String number,
            String status,
            boolean available,
            String reason,
            LocalDate conflictDate,
            String conflictCode,
            Long blockId) {}

    public List<Candidate> candidates(Window window, Long ownAllocation, boolean readyNow) {
        var result = new ArrayList<Candidate>();
        for (var room :
                db.rows(
                        """
                SELECT r.*,f.is_active AS floor_active FROM rooms r JOIN floors f ON f.id=r.floor_id
                WHERE r.room_type_id=? ORDER BY CASE WHEN r.status='AVAILABLE' THEN 0 ELSE 1 END,r.id
                """,
                        window.type())) {
            String reason = null;
            boolean valid =
                    !"INACTIVE".equals(room.s("status"))
                            && Boolean.TRUE.equals(room.data().get("floor_active"))
                            && !room.date("sell_from").isAfter(window.from())
                            && (room.s("sell_until") == null
                                    || !room.date("sell_until").isBefore(window.to()));
            if (!valid) reason = "saleWindow";
            var conflicts =
                    db.rows(
                            """
                    SELECT c.stay_date,c.room_block_id,b.booking_code FROM room_night_claims c
                    LEFT JOIN room_allocations a ON a.id=c.room_allocation_id
                    LEFT JOIN booking_rooms br ON br.id=a.booking_room_id LEFT JOIN bookings b ON b.id=br.booking_id
                    WHERE c.room_id=? AND c.stay_date>=? AND c.stay_date<?
                    AND (c.room_allocation_id IS NULL OR ? IS NULL OR c.room_allocation_id<>?)
                    ORDER BY c.stay_date LIMIT 1
                    """,
                            room.id("id"),
                            window.from(),
                            window.to(),
                            ownAllocation,
                            ownAllocation);
            var conflict = conflicts.isEmpty() ? null : conflicts.get(0);
            if (reason == null && conflict != null)
                reason = conflict.id("room_block_id") == null ? "bookingConflict" : "blockConflict";
            if (reason == null
                    && readyNow
                    && (!"AVAILABLE".equals(room.s("status"))
                            || inventory.openStay(room.id("id"))
                            || inventory.unverifiedCleaning(room.id("id"))
                            || db.count(
                                            """
                        SELECT COUNT(*) FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id
                        WHERE a.room_id=? AND a.status IN ('SCHEDULED','OCCUPIED') AND a.to_date<=?
                            AND br.expected_check_out_at>? AND (? IS NULL OR a.id<>?)
                        """,
                                            room.id("id"),
                                            window.from(),
                                            OperationsStore.ts(Instant.now()),
                                            ownAllocation,
                                            ownAllocation)
                                    > 0)) reason = "notReady";
            result.add(
                    new Candidate(
                            room.id("id"),
                            room.s("room_number"),
                            room.s("status"),
                            reason == null,
                            reason,
                            conflict == null ? null : conflict.date("stay_date"),
                            conflict == null ? null : conflict.s("booking_code"),
                            conflict == null ? null : conflict.id("room_block_id")));
        }
        return result;
    }

    public long choose(Window window, Long preferred, Long ownAllocation, Set<Long> excluded) {
        var candidates =
                candidates(window, ownAllocation, false).stream()
                        .filter(r -> preferred == null || preferred == r.id())
                        .filter(r -> !excluded.contains(r.id()))
                        .toList();
        var chosen = candidates.stream().filter(Candidate::available).findFirst();
        if (chosen.isPresent()) return chosen.get().id();
        String conflict = window.from() + " → " + window.to();
        if (!candidates.isEmpty()) {
            var c = candidates.get(0);
            conflict =
                    c.number()
                            + " / "
                            + Objects.toString(c.conflictDate(), window.from().toString())
                            + " / "
                            + Objects.toString(
                                    c.conflictCode(),
                                    c.blockId() == null
                                            ? Objects.toString(c.reason(), "")
                                            : "#" + c.blockId());
        }
        db.require(false, "conflict", conflict);
        throw new IllegalStateException();
    }

    public void assertClaims(long line, Window window) {
        var assigned =
                db.rows(
                        "SELECT * FROM room_allocations WHERE booking_room_id=? AND"
                                + " status='SCHEDULED'",
                        line);
        db.require(assigned.size() == 1, "state");
        var a = assigned.get(0);
        db.require(
                a.date("from_date").equals(window.from())
                        && a.date("to_date").equals(window.to())
                        && db.count(
                                        "SELECT COUNT(*) FROM rooms WHERE id=? AND room_type_id=?",
                                        a.id("room_id"),
                                        window.type())
                                == 1
                        && db.count(
                                        "SELECT COUNT(*) FROM room_night_claims WHERE"
                                            + " room_allocation_id=? AND room_id=? AND stay_date>=?"
                                            + " AND stay_date<?",
                                        a.id("id"),
                                        a.id("room_id"),
                                        window.from(),
                                        window.to())
                                == java.time.temporal.ChronoUnit.DAYS.between(
                                        window.from(), window.to()),
                "state");
    }

    public void lockRooms(Collection<Long> rooms) {
        for (var id : new TreeSet<>(rooms))
            db.one("SELECT id FROM rooms WHERE id=? FOR UPDATE", id);
    }

    public long schedule(long line, long room, LocalDate from, LocalDate to, String reason) {
        long allocation =
                db.insert(
                        "room_allocations",
                        "booking_room_id,room_id,from_date,to_date,assigned_by,reason",
                        line,
                        room,
                        from,
                        to,
                        db.actor(),
                        reason);
        claim(allocation, room, from, to);
        return allocation;
    }

    public void claim(long allocation, long room, LocalDate from, LocalDate to) {
        from.datesUntil(to)
                .forEach(
                        date ->
                                db.update(
                                        """
                INSERT INTO room_night_claims(room_id,stay_date,room_allocation_id) VALUES(?,?,?)
                """,
                                        room,
                                        date,
                                        allocation));
    }

    public void releaseClaims(long allocation, LocalDate from) {
        db.update(
                "DELETE FROM room_night_claims WHERE room_allocation_id=? AND stay_date>=?",
                allocation,
                from);
    }

    public void counters(long type, LocalDate day, int held, int reserved) {
        int changed =
                db.update(
                        """
                UPDATE room_type_inventory SET held_rooms=held_rooms+?, reserved_rooms=reserved_rooms+?,
                updated_at=CURRENT_TIMESTAMP(6) WHERE room_type_id=? AND stay_date=?
                AND held_rooms+?>=0 AND reserved_rooms+?>=0 AND held_rooms+reserved_rooms+?<=sellable_rooms
                """,
                        held,
                        reserved,
                        type,
                        day,
                        held,
                        reserved,
                        held + reserved);
        db.require(changed == 1, "capacity", day);
    }

    public void turnOver(long room, String reason) {
        db.update(
                "UPDATE rooms SET status='DIRTY',version=version+1,updated_at=CURRENT_TIMESTAMP(6)"
                        + " WHERE id=?",
                room);
        db.insert(
                "room_status_history",
                "room_id,old_status,new_status,changed_by,reason",
                room,
                "OCCUPIED",
                "DIRTY",
                db.actor(),
                reason);
        db.insert(
                "housekeeping_tasks",
                "room_id,created_by,notes",
                room,
                db.actor(),
                "Dọn phòng sau khi khách trả / đổi phòng · " + reason);
    }
}
