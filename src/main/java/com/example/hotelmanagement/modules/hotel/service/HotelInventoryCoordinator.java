package com.example.hotelmanagement.modules.hotel.service;

import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.hotel.repository.HotelSettingRepository;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class HotelInventoryCoordinator {
    private final HotelSettingRepository settings;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final CmsMessages messages;

    @Value("${app.hotel.inventory-days:730}")
    private int inventoryDays;

    public void lock() {
        settings.lockSingleton().orElseThrow(() -> messages.error("hotel.error.settingsMissing"));
    }

    public LocalDate today() {
        String zone =
                jdbc.queryForObject("SELECT timezone FROM hotel_settings WHERE id=1", String.class);
        return LocalDate.now(ZoneId.of(zone));
    }

    public LocalDate horizon() {
        return today().plusDays(Math.max(1, Math.min(inventoryDays, 3660)));
    }

    public void reconcile(Collection<Long> typeIds) {
        em.flush();
        LocalDate today = today(), until = horizon();
        for (Long typeId : new TreeSet<>(typeIds)) {
            var physical =
                    jdbc.query(
                            "SELECT sell_from,sell_until FROM rooms WHERE room_type_id=? AND"
                                    + " status<>'INACTIVE'",
                            (r, n) ->
                                    new SaleWindow(
                                            r.getDate(1).toLocalDate(),
                                            r.getDate(2) == null
                                                    ? null
                                                    : r.getDate(2).toLocalDate()),
                            typeId);
            Map<LocalDate, Integer> blocked = new HashMap<>();
            jdbc.query(
                    "SELECT c.stay_date,COUNT(*) FROM room_night_claims c JOIN rooms r ON"
                            + " r.id=c.room_id WHERE r.room_type_id=? AND c.stay_date>=? AND"
                            + " c.room_block_id IS NOT NULL GROUP BY c.stay_date",
                    r -> {
                        blocked.put(r.getDate(1).toLocalDate(), r.getInt(2));
                    },
                    typeId,
                    today);
            Map<LocalDate, Inventory> existing = new HashMap<>();
            jdbc.query(
                    "SELECT stay_date,total_rooms,blocked_rooms,held_rooms,reserved_rooms FROM"
                            + " room_type_inventory WHERE room_type_id=? AND stay_date>=? ORDER BY"
                            + " stay_date FOR UPDATE",
                    r -> {
                        existing.put(
                                r.getDate(1).toLocalDate(),
                                new Inventory(r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5)));
                    },
                    typeId,
                    today);
            Set<LocalDate> days = new TreeSet<>(existing.keySet());
            today.datesUntil(until).forEach(days::add);
            List<Object[]> inserts = new ArrayList<>(), updates = new ArrayList<>();
            for (LocalDate date : days) {
                int total =
                        (int)
                                physical.stream()
                                        .filter(
                                                w ->
                                                        !date.isBefore(w.from())
                                                                && (w.to() == null
                                                                        || date.isBefore(w.to())))
                                        .count();
                int unavailable = blocked.getOrDefault(date, 0);
                Inventory old = existing.get(date);
                if (unavailable > total
                        || (old != null && old.held() + old.reserved() > total - unavailable))
                    throw messages.error("hotel.error.capacityReserved");
                if (old == null)
                    inserts.add(
                            new Object[] {typeId, date, total, unavailable, total - unavailable});
                else if (old.total() != total || old.blocked() != unavailable)
                    updates.add(
                            new Object[] {total, unavailable, total - unavailable, typeId, date});
            }
            if (!inserts.isEmpty())
                jdbc.batchUpdate(
                        "INSERT INTO"
                            + " room_type_inventory(room_type_id,stay_date,total_rooms,blocked_rooms,sellable_rooms,held_rooms,reserved_rooms,updated_at)"
                            + " VALUES(?,?,?,?,?,0,0,CURRENT_TIMESTAMP(6))",
                        inserts);
            if (!updates.isEmpty())
                jdbc.batchUpdate(
                        "UPDATE room_type_inventory SET"
                            + " total_rooms=?,blocked_rooms=?,sellable_rooms=?,updated_at=CURRENT_TIMESTAMP(6)"
                            + " WHERE room_type_id=? AND stay_date=?",
                        updates);
        }
    }

    public boolean openStay(Long roomId) {
        return count(
                        "SELECT COUNT(*) FROM room_allocations WHERE room_id=? AND"
                                + " status='OCCUPIED' AND occupied_until_at IS NULL",
                        roomId)
                > 0;
    }

    public boolean unresolvedMaintenance(Long roomId) {
        return count(
                        "SELECT COUNT(*) FROM maintenance_requests m JOIN room_blocks b ON"
                            + " b.maintenance_request_id=m.id AND b.room_id=m.room_id WHERE"
                            + " m.room_id=? AND m.status IN ('OPEN','IN_PROGRESS') AND b.status IN"
                            + " ('ACTIVE','RELEASED') AND b.from_date<=?",
                        roomId,
                        today())
                > 0;
    }

    public boolean unverifiedCleaning(Long roomId) {
        return unresolvedMaintenance(roomId)
                || count(
                                "SELECT COUNT(*) FROM housekeeping_tasks WHERE room_id=? AND status"
                                    + " IN ('PENDING','IN_PROGRESS','COMPLETED')",
                                roomId)
                        > 0;
    }

    public boolean futureAllocations(Long roomId) {
        return count(
                        "SELECT COUNT(*) FROM room_allocations WHERE room_id=? AND status IN"
                                + " ('SCHEDULED','OCCUPIED') AND to_date>?",
                        roomId,
                        today())
                > 0;
    }

    public boolean currentBlock(Long roomId) {
        return count(
                        "SELECT COUNT(*) FROM room_night_claims WHERE room_id=? AND stay_date=? AND"
                                + " room_block_id IS NOT NULL",
                        roomId,
                        today())
                > 0;
    }

    public boolean futureBlocks(Long roomId) {
        return count(
                        "SELECT COUNT(*) FROM room_night_claims WHERE room_id=? AND stay_date>=?"
                                + " AND room_block_id IS NOT NULL",
                        roomId,
                        today())
                > 0;
    }

    public long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private record SaleWindow(LocalDate from, LocalDate to) {}

    private record Inventory(int total, int blocked, int held, int reserved) {}
}
