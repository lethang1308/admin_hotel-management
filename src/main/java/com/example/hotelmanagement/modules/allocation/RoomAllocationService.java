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

    public long choose(Window window, Long preferred, Long ownAllocation, Set<Long> excluded) {
        var candidates =
                db.rows(
                        """
                SELECT id,room_number FROM rooms
                WHERE room_type_id=? AND status<>'INACTIVE' AND sell_from<=?
                  AND (sell_until IS NULL OR sell_until>=?) AND (? IS NULL OR id=?)
                ORDER BY id
                """,
                        window.type(),
                        window.from(),
                        window.to(),
                        preferred,
                        preferred);
        String conflict = window.from() + " → " + window.to();
        for (var room : candidates) {
            long id = room.id("id");
            if (excluded.contains(id)) continue;
            var claims =
                    db.rows(
                            """
                    SELECT c.stay_date,c.room_block_id,b.booking_code FROM room_night_claims c
                    LEFT JOIN room_allocations a ON a.id=c.room_allocation_id
                    LEFT JOIN booking_rooms br ON br.id=a.booking_room_id
                    LEFT JOIN bookings b ON b.id=br.booking_id
                    WHERE c.room_id=? AND c.stay_date>=? AND c.stay_date<?
                      AND (c.room_allocation_id IS NULL OR ? IS NULL OR c.room_allocation_id<>?)
                    ORDER BY c.stay_date LIMIT 1
                    """,
                            id,
                            window.from(),
                            window.to(),
                            ownAllocation,
                            ownAllocation);
            if (claims.isEmpty()) return id;
            var claim = claims.get(0);
            conflict =
                    room.s("room_number")
                            + " / "
                            + claim.s("stay_date")
                            + " / "
                            + (claim.s("booking_code") == null
                                    ? "#" + claim.s("room_block_id")
                                    : claim.s("booking_code"));
        }
        db.require(false, "conflict", conflict);
        throw new IllegalStateException();
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
        db.insert("housekeeping_tasks", "room_id,created_by,notes", room, db.actor(), reason);
    }
}
