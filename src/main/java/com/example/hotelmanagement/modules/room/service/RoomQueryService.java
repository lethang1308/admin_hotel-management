package com.example.hotelmanagement.modules.room.service;

import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.room.dto.*;
import com.example.hotelmanagement.modules.room.entity.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('room.read','room.manage','room.block.manage')")
public class RoomQueryService {
    private final JdbcTemplate jdbc;
    private final HotelInventoryCoordinator guard;
    private final CmsMessages messages;
    private static final String SELECT =
            """
 SELECT r.*,t.name type_name,t.base_price,f.name floor_name,f.area_name,f.sort_order floor_sort,f.floor_number,
 CASE WHEN EXISTS(SELECT 1 FROM room_blocks x WHERE x.room_id=r.id AND x.status='ACTIVE' AND x.from_date<=? AND x.to_date>?) AND r.status<>'OCCUPIED' THEN 'MAINTENANCE' ELSE r.status END effective_status,
 (SELECT b.contact_name FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id JOIN bookings b ON b.id=br.booking_id WHERE a.room_id=r.id AND a.status='OCCUPIED' AND a.occupied_until_at IS NULL ORDER BY a.id DESC LIMIT 1) guest_name,
 (SELECT br.check_out_date FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id WHERE a.room_id=r.id AND a.status='OCCUPIED' AND a.occupied_until_at IS NULL ORDER BY a.id DESC LIMIT 1) expected_checkout
 FROM rooms r JOIN room_types t ON t.id=r.room_type_id JOIN floors f ON f.id=r.floor_id
 """;

    public RoomResponse get(Long id) {
        var rows =
                jdbc.query(SELECT + " WHERE r.id=?", this::map, guard.today(), guard.today(), id);
        if (rows.isEmpty()) throw messages.error("hotel.error.notFound");
        return rows.get(0);
    }

    public List<RoomResponse> all(
            Long floorId, String area, Long typeId, RoomStatus status, String search) {
        List<Object> args = new ArrayList<>(List.of(guard.today(), guard.today()));
        StringBuilder sql = new StringBuilder("SELECT q.* FROM (" + SELECT + ") q WHERE 1=1");
        if (floorId != null) {
            sql.append(" AND q.floor_id=?");
            args.add(floorId);
        }
        if (typeId != null) {
            sql.append(" AND q.room_type_id=?");
            args.add(typeId);
        }
        if (area != null && !area.isBlank()) {
            sql.append(" AND q.area_name=?");
            args.add(area);
        }
        if (status != null) {
            sql.append(" AND q.effective_status=?");
            args.add(status.name());
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND LOWER(q.room_number) LIKE ? ESCAPE '!'");
            args.add(
                    "%"
                            + search.trim()
                                    .toLowerCase(Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%");
        }
        sql.append(" ORDER BY q.floor_sort,q.floor_number,q.floor_id,q.room_number");
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    public PageResponse<RoomResponse> search(
            int page,
            int size,
            Long floorId,
            String area,
            Long typeId,
            RoomStatus status,
            String search) {
        var rows = all(floorId, area, typeId, status, search);
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, Math.min(page, Math.max(0, (rows.size() - 1) / safeSize)));
        int from = safePage * safeSize;
        return PageResponse.from(
                new org.springframework.data.domain.PageImpl<>(
                        rows.subList(from, Math.min(from + safeSize, rows.size())),
                        org.springframework.data.domain.PageRequest.of(safePage, safeSize),
                        rows.size()));
    }

    public RoomGridResponse grid(Long floorId, String area, Long typeId, RoomStatus status) {
        var rows = all(floorId, area, typeId, status, null);
        Map<Long, List<RoomResponse>> groups = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var s : RoomStatus.values()) counts.put(s.name(), 0L);
        for (var r : rows) {
            groups.computeIfAbsent(r.floorId(), k -> new ArrayList<>()).add(r);
            counts.merge(r.status().name(), 1L, Long::sum);
        }
        return new RoomGridResponse(
                groups.values().stream()
                        .map(
                                g ->
                                        new RoomGridResponse.FloorGroup(
                                                g.get(0).floorId(),
                                                g.get(0).floorName(),
                                                g.get(0).areaName(),
                                                g))
                        .toList(),
                counts,
                rows.size());
    }

    public List<String> areas() {
        return jdbc.queryForList(
                "SELECT DISTINCT area_name FROM floors WHERE area_name IS NOT NULL AND"
                        + " area_name<>'' ORDER BY area_name",
                String.class);
    }

    public List<RoomStayResponse> stays(Long id) {
        get(id);
        return jdbc.query(
                """
 SELECT b.booking_code,b.contact_name,b.contact_phone,a.from_date,a.to_date,a.occupied_from_at,a.occupied_until_at,a.status,u.full_name
 FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id JOIN bookings b ON b.id=br.booking_id LEFT JOIN users u ON u.id=br.checked_in_by WHERE a.room_id=? ORDER BY a.from_date DESC,a.id DESC
 """,
                (r, n) ->
                        new RoomStayResponse(
                                r.getString(1),
                                r.getString(2),
                                r.getString(3),
                                r.getDate(4).toLocalDate(),
                                r.getDate(5).toLocalDate(),
                                instant(r, 6),
                                instant(r, 7),
                                r.getString(8),
                                r.getString(9)),
                id);
    }

    public List<RoomHistoryResponse> history(Long id) {
        get(id);
        return jdbc.query(
                "SELECT h.id,h.old_status,h.new_status,u.full_name,h.reason,h.changed_at FROM"
                        + " room_status_history h LEFT JOIN users u ON u.id=h.changed_by WHERE"
                        + " room_id=? ORDER BY h.changed_at DESC,h.id DESC",
                (r, n) ->
                        new RoomHistoryResponse(
                                r.getLong(1),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                instant(r, 6)),
                id);
    }

    public List<RoomBlockResponse> blocks(Long roomId) {
        String where = roomId == null ? "" : " WHERE b.room_id=?";
        Object[] args = roomId == null ? new Object[] {} : new Object[] {roomId};
        return jdbc.query(
                "SELECT b.*,r.room_number,u.full_name FROM room_blocks b JOIN rooms r ON"
                        + " r.id=b.room_id LEFT JOIN users u ON u.id=b.created_by"
                        + where
                        + " ORDER BY b.created_at DESC,b.id DESC",
                (r, n) ->
                        new RoomBlockResponse(
                                r.getLong("id"),
                                r.getLong("room_id"),
                                r.getString("room_number"),
                                r.getString("block_type"),
                                r.getDate("from_date").toLocalDate(),
                                r.getDate("to_date").toLocalDate(),
                                r.getString("status"),
                                r.getString("reason"),
                                r.getString("full_name"),
                                instant(r, "created_at"),
                                instant(r, "released_at")),
                args);
    }

    /**
     * Future booking flow must also take the shared guard and create UNIQUE room_night_claims
     * atomically.
     */
    public boolean isAvailable(Long id, LocalDate from, LocalDate to) {
        var r = get(id);
        if (from == null
                || to == null
                || from.isBefore(guard.today())
                || !to.isAfter(from)
                || from.isBefore(r.sellFrom())
                || (r.sellUntil() != null && to.isAfter(r.sellUntil()))
                || r.status() == RoomStatus.INACTIVE
                || guard.openStay(id)) return false;
        if (!from.isAfter(guard.today()) && r.status() != RoomStatus.AVAILABLE) return false;
        if (guard.count(
                        "SELECT COUNT(*) FROM rooms r JOIN room_types t ON t.id=r.room_type_id JOIN"
                            + " floors f ON f.id=r.floor_id WHERE r.id=? AND t.is_active=true AND"
                            + " f.is_active=true",
                        id)
                != 1) return false;
        return guard.count(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_id=? AND"
                                        + " stay_date>=? AND stay_date<?",
                                id,
                                from,
                                to)
                        == 0
                && guard.count(
                                "SELECT COUNT(*) FROM room_blocks WHERE room_id=? AND"
                                        + " status='ACTIVE' AND from_date<? AND to_date>?",
                                id,
                                to,
                                from)
                        == 0
                && guard.count(
                                "SELECT COUNT(*) FROM room_allocations WHERE room_id=? AND status"
                                    + " IN ('SCHEDULED','OCCUPIED') AND from_date<? AND to_date>?",
                                id,
                                to,
                                from)
                        == 0;
    }

    private RoomResponse map(ResultSet r, int n) throws SQLException {
        return new RoomResponse(
                r.getLong("id"),
                r.getString("room_number"),
                r.getLong("room_type_id"),
                r.getString("type_name"),
                r.getBigDecimal("base_price"),
                r.getLong("floor_id"),
                r.getString("floor_name"),
                r.getString("area_name"),
                r.getInt("floor_sort"),
                r.getInt("floor_number"),
                RoomStatus.valueOf(r.getString("effective_status")),
                r.getDate("sell_from").toLocalDate(),
                r.getDate("sell_until") == null ? null : r.getDate("sell_until").toLocalDate(),
                r.getString("notes"),
                r.getInt("version"),
                r.getString("guest_name"),
                r.getDate("expected_checkout") == null
                        ? null
                        : r.getDate("expected_checkout").toLocalDate());
    }

    private static Instant instant(ResultSet r, int col) throws SQLException {
        var t = r.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Instant instant(ResultSet r, String col) throws SQLException {
        var t = r.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
