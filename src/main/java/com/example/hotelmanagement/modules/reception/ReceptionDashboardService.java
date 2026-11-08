package com.example.hotelmanagement.modules.reception;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAnyAuthority('booking.read.any','report.shift')")
public class ReceptionDashboardService {
    private final OperationsStore db;

    public record Kpis(
            Long arrivals,
            Long departures,
            Long occupied,
            Long ready,
            Long attention,
            Long pending) {}

    public record Queue(
            long bookingId,
            Long lineId,
            String code,
            String name,
            String phone,
            String source,
            String status,
            String type,
            String room,
            String roomStatus,
            Instant expected,
            Instant expires,
            int adults,
            int children,
            int rooms,
            BigDecimal total,
            BigDecimal paid,
            BigDecimal debt,
            BigDecimal depositDue) {}

    public record Shift(
            Long id,
            boolean employeeAvailable,
            Instant openedAt,
            BigDecimal opening,
            BigDecimal collected,
            BigDecimal refunded,
            BigDecimal expected,
            String notes) {}

    public record Dashboard(
            LocalDate today,
            String timezone,
            Instant now,
            Kpis kpis,
            Shift shift,
            List<Queue> arrivals,
            List<Queue> departures,
            List<Queue> pending,
            List<Queue> lateArrivals,
            List<Queue> lateDepartures) {}

    public Dashboard snapshot() {
        var permissions =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(Object::toString)
                        .toList();
        boolean bookings = permissions.contains("booking.read.any"),
                rooms = permissions.contains("room.read");
        Instant now = Instant.now();
        var today = db.today();
        var arrivals =
                bookings
                        ? queue(
                                "br.check_in_date=? AND br.status IN ('PENDING','CONFIRMED')",
                                today)
                        : List.<Queue>of();
        var departures =
                bookings
                        ? queue("br.check_out_date=? AND br.status='CHECKED_IN'", today)
                        : List.<Queue>of();
        var pending = bookings ? pending(now) : List.<Queue>of();
        var lateArrivals =
                bookings
                        ? queue(
                                "br.status='CONFIRMED' AND br.expected_check_in_at<=?",
                                ts(now.minus(Duration.ofHours(2))))
                        : List.<Queue>of();
        var lateDepartures =
                bookings
                        ? queue("br.status='CHECKED_IN' AND br.expected_check_out_at<?", ts(now))
                        : List.<Queue>of();
        var kpi =
                new Kpis(
                        bookings
                                ? db.count(
                                        "SELECT COUNT(*) FROM booking_rooms WHERE check_in_date=?"
                                                + " AND status IN ('PENDING','CONFIRMED')",
                                        today)
                                : null,
                        bookings
                                ? db.count(
                                        "SELECT COUNT(*) FROM booking_rooms WHERE check_out_date=?"
                                                + " AND status='CHECKED_IN'",
                                        today)
                                : null,
                        rooms
                                ? db.count(
                                        "SELECT COUNT(DISTINCT room_id) FROM room_allocations WHERE"
                                            + " status='OCCUPIED' AND occupied_until_at IS NULL")
                                : null,
                        rooms
                                ? db.count(
                                        """
                    SELECT COUNT(*) FROM rooms r JOIN room_types t ON t.id=r.room_type_id JOIN floors f ON f.id=r.floor_id
                    WHERE r.status='AVAILABLE' AND t.is_active=TRUE AND f.is_active=TRUE AND r.sell_from<=?
                    AND (r.sell_until IS NULL OR r.sell_until>?)
                    AND NOT EXISTS(SELECT 1 FROM room_allocations a WHERE a.room_id=r.id AND a.status='OCCUPIED' AND a.occupied_until_at IS NULL)
                    AND NOT EXISTS(SELECT 1 FROM room_night_claims c WHERE c.room_id=r.id AND c.stay_date=? AND c.room_block_id IS NOT NULL)
                    AND NOT EXISTS(SELECT 1 FROM housekeeping_tasks h WHERE h.room_id=r.id AND h.status IN ('PENDING','IN_PROGRESS','COMPLETED'))
                    """,
                                        today,
                                        today,
                                        today)
                                : null,
                        rooms
                                ? db.count(
                                        "SELECT COUNT(*) FROM rooms WHERE status IN"
                                                + " ('DIRTY','CLEANING','MAINTENANCE')")
                                : null,
                        bookings
                                ? db.count(
                                        "SELECT COUNT(*) FROM bookings WHERE status='PENDING' AND"
                                                + " hold_expires_at>?",
                                        ts(now))
                                : null);
        return new Dashboard(
                today,
                db.zone().getId(),
                now,
                kpi,
                permissions.contains("report.shift") ? shift() : null,
                arrivals,
                departures,
                pending,
                lateArrivals,
                lateDepartures);
    }

    private static final String BALANCES =
            """
            ,COALESCE((SELECT SUM(c.amount) FROM booking_charges c WHERE c.booking_id=b.id AND c.status='POSTED'),0) AS total
            ,COALESCE((SELECT SUM(CASE WHEN p.kind='REFUND' THEN -p.amount ELSE p.amount END)
                FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCEEDED'),0) AS paid
            """;

    private List<Queue> queue(String filter, Object value) {
        return db
                .rows(
                        """
                SELECT b.id AS booking_id,br.id AS line_id,b.booking_code,b.contact_name,b.contact_phone,b.source,br.status,
                    br.room_type_name_snapshot AS type_name,r.room_number,r.status AS room_status,br.adults,br.children,
                    CASE WHEN br.status='CHECKED_IN' THEN br.expected_check_out_at ELSE br.expected_check_in_at END AS expected,
                    b.hold_expires_at,b.deposit_required_amount,1 AS room_count
                """
                                + BALANCES
                                + """
                FROM booking_rooms br JOIN bookings b ON b.id=br.booking_id
                LEFT JOIN room_allocations a ON a.id=(SELECT MAX(ra.id) FROM room_allocations ra WHERE ra.booking_room_id=br.id
                    AND ra.status IN ('SCHEDULED','OCCUPIED'))
                LEFT JOIN rooms r ON r.id=a.room_id WHERE
                """
                                + filter
                                + " ORDER BY expected,br.id LIMIT 50",
                        value)
                .stream()
                .map(this::row)
                .toList();
    }

    private List<Queue> pending(Instant now) {
        return db
                .rows(
                        """
                SELECT b.id AS booking_id,NULL AS line_id,b.booking_code,b.contact_name,b.contact_phone,b.source,b.status,
                    NULL AS type_name,NULL AS room_number,NULL AS room_status,0 AS adults,0 AS children,
                    (SELECT MIN(br.expected_check_in_at) FROM booking_rooms br WHERE br.booking_id=b.id) AS expected,
                    (SELECT COUNT(*) FROM booking_rooms br WHERE br.booking_id=b.id AND br.status='PENDING') AS room_count,
                    b.hold_expires_at,b.deposit_required_amount
                """
                                + BALANCES
                                + """
                FROM bookings b WHERE b.status='PENDING' AND b.hold_expires_at>?
                ORDER BY b.hold_expires_at,b.id LIMIT 50
                """,
                        ts(now))
                .stream()
                .map(this::row)
                .toList();
    }

    private Queue row(Row r) {
        var total = r.money("total");
        var paid = r.money("paid");
        return new Queue(
                r.id("booking_id"),
                r.id("line_id"),
                r.s("booking_code"),
                r.s("contact_name"),
                r.s("contact_phone"),
                r.s("source"),
                r.s("status"),
                r.s("type_name"),
                r.s("room_number"),
                r.s("room_status"),
                r.time("expected"),
                r.time("hold_expires_at"),
                r.n("adults"),
                r.n("children"),
                r.n("room_count"),
                total,
                paid,
                total.subtract(paid),
                r.money("deposit_required_amount").min(total.max(ZERO)).subtract(paid).max(ZERO));
    }

    private Shift shift() {
        var employee =
                db.rows(
                        "SELECT id FROM employees WHERE user_id=? AND ended_on IS NULL",
                        db.actor());
        if (employee.isEmpty()) return new Shift(null, false, null, ZERO, ZERO, ZERO, ZERO, null);
        var open =
                db.rows(
                        "SELECT * FROM work_sessions WHERE employee_id=? AND closed_at IS NULL"
                                + " ORDER BY id DESC LIMIT 1",
                        employee.get(0).id("id"));
        if (open.isEmpty()) return new Shift(null, true, null, ZERO, ZERO, ZERO, ZERO, null);
        var s = open.get(0);
        var amounts =
                db.one(
                        """
                SELECT COALESCE(SUM(CASE WHEN kind<>'REFUND' THEN amount ELSE 0 END),0) AS collected,
                COALESCE(SUM(CASE WHEN kind='REFUND' THEN amount ELSE 0 END),0) AS refunded
                FROM payment_transactions WHERE work_session_id=? AND status='SUCCEEDED' AND method='CASH'
                """,
                        s.id("id"));
        var collected = amounts.money("collected");
        var refunded = amounts.money("refunded");
        return new Shift(
                s.id("id"),
                true,
                s.time("opened_at"),
                s.money("opening_cash"),
                collected,
                refunded,
                s.money("opening_cash").add(collected).subtract(refunded),
                s.s("notes"));
    }
}
