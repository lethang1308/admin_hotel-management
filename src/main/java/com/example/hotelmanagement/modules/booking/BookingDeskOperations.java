package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.customer.service.IdentityDocumentCrypto;
import com.example.hotelmanagement.modules.payment.FrontDeskPaymentService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BookingDeskOperations {
    private final OperationsStore db;
    private final BookingService bookings;
    private final RoomAllocationService allocation;
    private final IdentityDocumentCrypto documents;
    private final FrontDeskPaymentService payments;
    private final StayService stays;

    @PreAuthorize("hasAuthority('booking.update.any') and hasAuthority('booking.read.any')")
    @Transactional(readOnly = true)
    public List<RoomAllocationService.Candidate> candidates(
            long booking, long line, Long requestedType, LocalDate until) {
        var br = db.bookingRoom(booking, line);
        db.require(Set.of("PENDING", "CONFIRMED", "CHECKED_IN").contains(br.s("status")), "state");
        var assigned = bookings.activeAllocation(line);
        var from = br.s("status").equals("CHECKED_IN") ? db.today() : br.date("check_in_date");
        long type = requestedType == null ? br.id("room_type_id") : requestedType;
        if (br.s("status").equals("PENDING")) db.require(type == br.id("room_type_id"), "state");
        bookings.capacity(type, br.n("adults"), br.n("children"));
        var window =
                new RoomAllocationService.Window(
                        type, from, until == null ? br.date("check_out_date") : until);
        bookings.validateWindow(window);
        return allocation.candidates(window, assigned.id("id"), from.equals(db.today()));
    }

    @PreAuthorize("hasAuthority('booking.update.any')")
    public void assign(long booking, long line, long room, String reason) {
        var order = bookings.lock(booking);
        var br = db.bookingRoom(booking, line);
        db.require(Set.of("PENDING", "CONFIRMED").contains(br.s("status")), "state");
        if ("PENDING".equals(br.s("status")))
            db.require(
                    order.time("hold_expires_at") != null
                            && Instant.now().isBefore(order.time("hold_expires_at")),
                    "state");
        var old = bookings.activeAllocation(line);
        if (old.id("room_id") == room) return;
        reason = db.required(reason, 500);
        var window = bookings.window(br);
        bookings.validateWindow(window);
        allocation.lockInventory(List.of(window));
        allocation.lockRooms(List.of(old.id("room_id"), room));
        var chosen =
                allocation
                        .candidates(window, old.id("id"), window.from().equals(db.today()))
                        .stream()
                        .filter(c -> c.id() == room)
                        .findFirst()
                        .orElse(null);
        db.require(chosen != null, "input");
        db.require(
                chosen.available(),
                "conflict",
                chosen.number()
                        + " / "
                        + Objects.toString(chosen.conflictDate(), "")
                        + " / "
                        + Objects.toString(
                                chosen.conflictCode(),
                                chosen.blockId() == null
                                        ? Objects.toString(chosen.reason(), "")
                                        : "#" + chosen.blockId()));
        allocation.releaseClaims(old.id("id"), window.from());
        db.update(
                "UPDATE room_allocations SET status='RELEASED',ended_by=?,reason=? WHERE id=?",
                db.actor(),
                reason,
                old.id("id"));
        allocation.schedule(line, room, window.from(), window.to(), reason);
        db.update(
                "UPDATE bookings SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                booking);
        bookings.history(booking, line, br.s("status"), br.s("status"), reason);
        db.audit(
                "BOOKING_ROOM_REASSIGN",
                "BOOKING",
                booking,
                Map.of("roomId", old.id("room_id")),
                Map.of("roomId", room, "line", line),
                reason);
        db.event("ASSIGN:" + booking + ":" + (order.n("version") + 1), "BOOKING_CHANGED", booking);
    }

    public record CustomerMatch(
            long id,
            String code,
            String name,
            String phone,
            String email,
            String nationality,
            String document) {}

    @PreAuthorize("hasAuthority('booking.create') and hasAuthority('customer.read')")
    @Transactional(readOnly = true)
    public List<CustomerMatch> customers(String query, String mode) {
        query = db.required(query, 150);
        db.require(query.length() >= 3 && Set.of("CONTACT", "DOCUMENT").contains(mode), "input");
        List<Row> rows;
        if (mode.equals("DOCUMENT")) {
            db.require(documents.configured(), "input");
            rows =
                    db.rows(
                            "SELECT * FROM customers WHERE document_number_hash=? ORDER BY id DESC"
                                + " LIMIT 20",
                            documents.hash(query));
        } else {
            String normalized = query.replaceAll("[\\s().-]", "");
            String phone = like(normalized), text = like(query);
            rows =
                    db.rows(
                            """
                    SELECT * FROM customers WHERE LOWER(full_name) LIKE ? ESCAPE '!' OR LOWER(email) LIKE ? ESCAPE '!'
                    OR LOWER(customer_code) LIKE ? ESCAPE '!'
                    OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(phone,' ',''),'-',''),'.',''),'(',''),')','') LIKE ? ESCAPE '!'
                    ORDER BY id DESC LIMIT 20
                    """,
                            text,
                            text,
                            text,
                            phone);
        }
        return rows.stream()
                .map(
                        r ->
                                new CustomerMatch(
                                        r.id("id"),
                                        r.s("customer_code"),
                                        r.s("full_name"),
                                        r.s("phone"),
                                        r.s("email"),
                                        r.s("nationality_code"),
                                        r.s("document_number_encrypted") == null
                                                        || !documents.configured()
                                                ? null
                                                : documents.masked(
                                                        r.s("document_number_encrypted"))))
                .toList();
    }

    @PreAuthorize("hasAuthority('booking.confirm') and hasAuthority('booking.checkin')")
    public void walkIn(
            long booking,
            long line,
            List<StayService.Guest> guests,
            String reason,
            String key,
            String method,
            BigDecimal amount) {
        var order = bookings.lock(booking);
        db.require("WALK_IN".equals(order.s("source")), "state");
        var br = db.bookingRoom(booking, line);
        if ("CHECKED_IN".equals(br.s("status"))) return;
        db.require(
                db.today().equals(br.date("check_in_date"))
                        && Set.of("PENDING", "CONFIRMED").contains(br.s("status")),
                "arrival");
        reason = db.required(reason, 500);
        db.require(amount != null && amount.signum() >= 0, "amount");
        if (amount.signum() > 0) payments.collect(booking, key, "PAYMENT", method, amount, reason);
        if ("PENDING".equals(br.s("status"))) bookings.confirm(booking, reason);
        stays.checkIn(booking, line, guests, reason);
    }
}
