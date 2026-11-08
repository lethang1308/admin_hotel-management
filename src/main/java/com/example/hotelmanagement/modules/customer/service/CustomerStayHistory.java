package com.example.hotelmanagement.modules.customer.service;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustomerStayHistory {
    private final OperationsStore db;

    public record Summary(
            long bookings,
            long visits,
            long nights,
            BigDecimal spent,
            String tag,
            List<Row> orders,
            List<Row> rooms,
            List<Row> services) {}

    @PreAuthorize("hasAuthority('customer.read')")
    public Summary get(long customer) {
        db.one("SELECT id FROM customers WHERE id=?", customer);
        var orders =
                db.rows(
                        """
   SELECT b.id,b.booking_code,b.status,b.created_at,
   COALESCE((SELECT SUM(p.amount) FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCEEDED' AND p.kind<>'REFUND'),0)
   -COALESCE((SELECT SUM(p.amount) FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCEEDED' AND p.kind='REFUND'),0) AS net_paid,
   COALESCE((SELECT SUM(c.amount) FROM booking_charges c WHERE c.booking_id=b.id AND c.status='POSTED'),0) AS total,
   (SELECT MAX(i.id) FROM invoices i WHERE i.booking_id=b.id AND i.status='ISSUED') AS invoice
   FROM bookings b WHERE b.customer_id=? ORDER BY b.id DESC
   """,
                        customer);
        // One booking counts as a visit even after a room move or when several rooms are booked.
        var actual =
                db.rows(
                        "SELECT br.*,b.booking_code FROM booking_rooms br JOIN bookings b ON"
                            + " b.id=br.booking_id WHERE b.customer_id=? AND br.actual_check_in_at"
                            + " IS NOT NULL ORDER BY br.id DESC",
                        customer);
        long visits = actual.stream().map(x -> x.id("booking_id")).distinct().count();
        long nights = 0;
        for (var r : actual) {
            var until = r.time("actual_check_out_at");
            LocalDate end = (until == null ? Instant.now() : until).atZone(db.zone()).toLocalDate();
            nights +=
                    Math.max(
                            1,
                            ChronoUnit.DAYS.between(
                                    r.time("actual_check_in_at").atZone(db.zone()).toLocalDate(),
                                    end));
        }
        var rooms =
                db.rows(
                        """
   SELECT b.id AS booking_id,b.booking_code,br.room_type_name_snapshot,r.room_number,a.from_date,a.to_date,a.occupied_from_at,a.occupied_until_at,a.status
   FROM bookings b JOIN booking_rooms br ON br.booking_id=b.id JOIN room_allocations a ON a.booking_room_id=br.id JOIN rooms r ON r.id=a.room_id
   WHERE b.customer_id=? AND a.occupied_from_at IS NOT NULL ORDER BY a.id DESC LIMIT 200
   """,
                        customer);
        var services =
                db.rows(
                        "SELECT"
                            + " s.service_name_snapshot,s.quantity,s.unit_snapshot,s.status,s.used_at,b.booking_code"
                            + " FROM service_usages s JOIN bookings b ON b.id=s.booking_id WHERE"
                            + " b.customer_id=? ORDER BY s.id DESC LIMIT 200",
                        customer);
        BigDecimal spent =
                orders.stream().map(x -> x.money("net_paid")).reduce(ZERO, BigDecimal::add);
        return new Summary(
                orders.size(),
                visits,
                nights,
                spent,
                visits >= 5 ? "VIP" : visits >= 2 ? "RETURNING" : "NEW",
                orders.stream().limit(100).toList(),
                rooms,
                services);
    }

    @Transactional
    @PreAuthorize("hasAuthority('customer.update')")
    public void notes(long customer, String expected, String notes, String reason) {
        var c = db.one("SELECT notes FROM customers WHERE id=? FOR UPDATE", customer);
        db.require(
                Objects.equals(Objects.toString(c.s("notes"), ""), Objects.toString(expected, "")),
                "priceChanged");
        notes = db.optional(notes, 5000);
        reason = db.required(reason, 500);
        db.update(
                "UPDATE customers SET notes=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                notes,
                customer);
        db.audit(
                "CUSTOMER_PREFERENCES",
                "CUSTOMER",
                customer,
                Map.of("notes", Objects.toString(c.s("notes"), "")),
                Map.of("notes", Objects.toString(notes, "")),
                reason);
    }
}
