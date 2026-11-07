package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.customer.service.IdentityDocumentCrypto;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.payment.BookingLedger;
import com.example.hotelmanagement.modules.payment.FrontDeskPaymentService;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class StayService {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;
    private final RoomAllocationService allocation;
    private final BookingService bookings;
    private final BookingLedger ledger;
    private final FrontDeskPaymentService payments;
    private final IdentityDocumentCrypto documents;

    public record Guest(String name, String type, String documentType, String document) {}

    @PreAuthorize("hasAuthority('booking.checkin')")
    public void checkIn(long id, long lineId, List<Guest> guests, String reason) {
        var booking = bookings.lock(id);
        var line = db.bookingRoom(id, lineId);
        if (line.s("status").equals("CHECKED_IN")) return;
        reason = db.required(reason, 500);
        Instant now = Instant.now();
        LocalDate today = db.today();
        db.require(
                line.s("status").equals("CONFIRMED") && today.equals(line.date("check_in_date")),
                "arrival");
        bookings.requireDeposit(booking);
        var assigned = bookings.activeAllocation(lineId);
        long room = assigned.id("room_id");
        allocation.lockRooms(List.of(room));
        db.require(
                "AVAILABLE".equals(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                        && !inventory.openStay(room)
                        && !inventory.currentBlock(room),
                "notReady");
        db.require(
                db.count(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_allocation_id=?"
                                        + " AND stay_date=?",
                                assigned.id("id"),
                                today)
                        == 1,
                "notReady");
        // Arriving early must not take the previous guest's checkout window.
        db.require(
                db.count(
                                """
                SELECT COUNT(*) FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id
                WHERE a.room_id=? AND a.id<>? AND a.status IN ('SCHEDULED','OCCUPIED')
                  AND a.to_date<=? AND br.expected_check_out_at>?
                """,
                                room,
                                assigned.id("id"),
                                today,
                                ts(now))
                        == 0,
                "notReady");
        db.require(
                guests != null
                        && guests.size() == line.n("adults") + line.n("children")
                        && guests.stream().filter(g -> "ADULT".equals(g.type())).count()
                                == line.n("adults"),
                "guests");
        timingSurcharge(booking, line, now, true);
        for (var guest : guests) {
            boolean adult = "ADULT".equals(guest.type());
            db.require(adult || "CHILD".equals(guest.type()), "guests");
            String document =
                    adult ? db.required(guest.document(), 60) : db.optional(guest.document(), 60);
            long guestId =
                    db.insert(
                            "booking_guests",
                            "booking_id,full_name,guest_type,document_type,document_number_encrypted,document_number_hash",
                            id,
                            db.required(guest.name(), 150),
                            guest.type(),
                            document == null ? null : db.required(guest.documentType(), 30),
                            document == null ? null : documents.encrypt(document),
                            documents.hash(document));
            db.insert(
                    "stay_guests",
                    "booking_id,booking_room_id,booking_guest_id,room_allocation_id,arrived_at",
                    id,
                    lineId,
                    guestId,
                    assigned.id("id"),
                    ts(now));
        }
        db.update(
                "UPDATE room_allocations SET status='OCCUPIED',occupied_from_at=? WHERE id=?",
                ts(now),
                assigned.id("id"));
        occupyRoom(room, reason);
        db.update(
                "UPDATE booking_rooms SET actual_check_in_at=?,checked_in_by=? WHERE id=?",
                ts(now),
                db.actor(),
                lineId);
        bookings.setLine(id, line, "CHECKED_IN", reason);
        bookings.aggregate(booking, reason);
        db.event("LINE:" + lineId + ":CHECKIN", "BOOKING_ROOM_CHECKED_IN", id);
        db.audit(
                "BOOKING_CHECK_IN",
                "BOOKING",
                id,
                Map.of(),
                Map.of("room", room, "line", lineId),
                reason);
    }

    void occupyRoom(long room, String reason) {
        db.update(
                "UPDATE rooms SET"
                    + " status='OCCUPIED',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " id=?",
                room);
        db.insert(
                "room_status_history",
                "room_id,old_status,new_status,changed_by,reason",
                room,
                "AVAILABLE",
                "OCCUPIED",
                db.actor(),
                reason);
    }

    @PreAuthorize("hasAuthority('booking.checkout')")
    public void checkOut(long id, long lineId, String reason) {
        completeCheckout(id, lineId, reason, null);
    }

    public record Settlement(
            String key, String method, BigDecimal amount, BigDecimal expectedDebt) {}

    @PreAuthorize("hasAuthority('booking.checkout')")
    public void checkOutAndCollect(long id, long lineId, String reason, Settlement settlement) {
        completeCheckout(id, lineId, reason, settlement);
    }

    private void completeCheckout(long id, long lineId, String reason, Settlement settlement) {
        var booking = bookings.lock(id);
        var line = db.bookingRoom(id, lineId);
        if (line.s("status").equals("CHECKED_OUT")) return;
        reason = db.required(reason, 500);
        db.require(line.s("status").equals("CHECKED_IN"), "state");
        var assigned = bookings.activeAllocation(lineId);
        allocation.lockInventory(List.of(bookings.window(line)));
        long room = assigned.id("room_id");
        allocation.lockRooms(List.of(room));
        Instant now = Instant.now();
        LocalDate today = db.today();
        LocalDate cutoff =
                today.isAfter(assigned.date("from_date"))
                        ? today
                        : assigned.date("from_date").plusDays(1);
        var nights = bookings.nights(lineId);
        if (cutoff.isBefore(line.date("check_out_date"))) {
            ledger.editable(id);
            var unused = new ArrayList<OperationsStore.Row>();
            for (var night : nights) {
                if (!night.date("stay_date").isBefore(cutoff)) unused.add(night);
            }
            var fee = BookingService.unusedNightsFee(bookings.policy(booking), unused, now);
            if (fee.signum() > 0)
                ledger.charge(
                        id,
                        lineId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "EARLY-OUT-FEE:" + lineId,
                        "CANCELLATION",
                        "Phí trả phòng sớm / Early checkout fee",
                        today,
                        BigDecimal.ONE,
                        fee,
                        null);
            allocation.releaseClaims(assigned.id("id"), cutoff);
            db.update(
                    "UPDATE room_allocations SET to_date=? WHERE id=?", cutoff, assigned.id("id"));
        }
        timingSurcharge(booking, line, now, false);
        for (var night : nights) {
            if (night.date("stay_date").isBefore(cutoff)) {
                db.update(
                        "UPDATE booking_room_nights SET stay_status='STAYED',recognized_at=? WHERE"
                                + " id=?",
                        ts(now),
                        night.id("id"));
            } else bookings.releaseNight(id, lineId, night, null, "EARLY-OUT", "CANCELLED");
        }
        boolean finalRoom =
                bookings.lines(id).stream()
                        .noneMatch(
                                l ->
                                        l.id("id") != lineId
                                                && Set.of("PENDING", "CONFIRMED", "CHECKED_IN")
                                                        .contains(l.s("status")));
        if (settlement != null) {
            db.require(
                    settlement.expectedDebt() != null
                            && ledger.balance(id).debt().compareTo(settlement.expectedDebt()) == 0,
                    "priceChanged");
            db.require(settlement.amount() != null && settlement.amount().signum() >= 0, "amount");
            if (settlement.amount().signum() > 0)
                payments.collect(
                        id,
                        settlement.key(),
                        "PAYMENT",
                        settlement.method(),
                        settlement.amount(),
                        reason);
        }
        if (finalRoom) {
            var debt = ledger.balance(id).debt();
            db.require(debt.signum() <= 0, "debt", debt);
        }
        endStay(assigned.id("id"), now);
        db.update(
                "UPDATE booking_rooms SET actual_check_out_at=?,checked_out_by=? WHERE id=?",
                ts(now),
                db.actor(),
                lineId);
        allocation.turnOver(room, reason);
        bookings.setLine(id, line, "CHECKED_OUT", reason);
        bookings.aggregate(booking, reason);
        db.event("LINE:" + lineId + ":CHECKOUT", "BOOKING_ROOM_CHECKED_OUT", id);
        db.audit(
                "BOOKING_CHECK_OUT",
                "BOOKING",
                id,
                Map.of(),
                Map.of("room", room, "line", lineId),
                reason);
    }

    void endStay(long allocationId, Instant now) {
        db.update(
                "UPDATE room_allocations SET status='COMPLETED',occupied_until_at=?,ended_by=?"
                        + " WHERE id=?",
                ts(now),
                db.actor(),
                allocationId);
        db.update(
                "UPDATE stay_guests SET departed_at=? WHERE room_allocation_id=? AND departed_at IS"
                        + " NULL",
                ts(now),
                allocationId);
    }

    private void timingSurcharge(Row booking, Row line, Instant now, boolean checkIn) {
        long id = booking.id("id");
        Instant expected = line.time(checkIn ? "expected_check_in_at" : "expected_check_out_at");
        Duration delta =
                checkIn ? Duration.between(now, expected) : Duration.between(expected, now);
        if (delta.isNegative() || delta.isZero()) return;
        String kind = checkIn ? "EARLY_CHECKIN" : "LATE_CHECKOUT";
        if (!checkIn
                && db.count(
                                """
                SELECT COUNT(*) FROM room_allocations next_stay JOIN booking_rooms next_line ON next_line.id=next_stay.booking_room_id
                WHERE next_stay.room_id=(SELECT room_id FROM room_allocations WHERE booking_room_id=? AND status='OCCUPIED')
                  AND next_stay.status='SCHEDULED' AND next_line.expected_check_in_at<=?
                """,
                                line.id("id"),
                                ts(now))
                        > 0)
            db.audit(
                    "ROOM_LATE_DEPARTURE_CONFLICT",
                    "BOOKING",
                    id,
                    Map.of(),
                    Map.of("line", line.id("id")),
                    null);
        var quote = surcharge(booking, line, now, checkIn);
        if (quote == null) return;
        ledger.editable(id);
        var ruleId = quote.policyId();
        var amount = quote.amount();
        var taxRate = quote.taxPercent();
        ledger.charge(
                id,
                line.id("id"),
                null,
                null,
                null,
                ruleId,
                null,
                kind + ":" + line.id("id"),
                "SURCHARGE",
                quote.name(),
                db.today(),
                BigDecimal.ONE,
                amount,
                taxRate);
        BigDecimal tax = percent(amount, taxRate);
        if (tax.signum() > 0)
            ledger.charge(
                    id,
                    line.id("id"),
                    null,
                    null,
                    null,
                    ruleId,
                    null,
                    kind + "-TAX:" + line.id("id"),
                    "TAX",
                    "VAT · " + quote.name(),
                    db.today(),
                    BigDecimal.ONE,
                    tax,
                    taxRate);
    }

    public record Surcharge(Long policyId, String name, BigDecimal amount, BigDecimal taxPercent) {
        @JsonProperty
        public BigDecimal total() {
            return amount.add(percent(amount, taxPercent));
        }
    }

    private Surcharge surcharge(Row booking, Row line, Instant now, boolean checkIn) {
        Instant expected = line.time(checkIn ? "expected_check_in_at" : "expected_check_out_at");
        Duration delta =
                checkIn ? Duration.between(now, expected) : Duration.between(expected, now);
        if (delta.isNegative() || delta.isZero()) return null;
        long minutes = Math.max(1, (delta.getSeconds() + 59) / 60);
        String kind = checkIn ? "EARLY_CHECKIN" : "LATE_CHECKOUT";
        var rules =
                db.rows(
                        """
                SELECT * FROM surcharge_policies WHERE is_active=TRUE AND kind=?
                AND (room_type_id IS NULL OR room_type_id=?)
                AND (from_minutes IS NULL OR from_minutes<=?) AND (to_minutes IS NULL OR to_minutes>?)
                ORDER BY priority DESC,id DESC LIMIT 1
                """,
                        kind,
                        line.id("room_type_id"),
                        minutes,
                        minutes);
        if (rules.isEmpty()) return null;
        var rule = rules.get(0);
        var nights = bookings.nights(line.id("id"));
        BigDecimal base = nights.get(checkIn ? 0 : nights.size() - 1).money("net_room_amount");
        BigDecimal value = rule.money("amount_value");
        BigDecimal amount =
                switch (rule.s("amount_type")) {
                    case "PERCENT_NIGHT" -> percent(base, value);
                    case "PER_HOUR" -> value.multiply(BigDecimal.valueOf(minutes))
                            .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
                    default -> vnd(value);
                };
        var taxRate = bookings.policy(booking).taxPercent();

        return new Surcharge(rule.id("id"), rule.s("name"), amount, taxRate);
    }

    public record StayPreview(
            BigDecimal currentDebt,
            BigDecimal projectedDebt,
            BigDecimal credit,
            BigDecimal changeFee,
            BigDecimal depositShortfall,
            Surcharge surcharge,
            int adults,
            int children,
            String status,
            String roomStatus,
            boolean finalRoom) {}

    @PreAuthorize("hasAuthority('booking.checkin')")
    @Transactional(readOnly = true)
    public StayPreview previewCheckIn(long id, long lineId) {
        return preview(id, lineId, true);
    }

    @PreAuthorize("hasAuthority('booking.checkout')")
    @Transactional(readOnly = true)
    public StayPreview previewCheckOut(long id, long lineId) {
        return preview(id, lineId, false);
    }

    private StayPreview preview(long id, long lineId, boolean checkIn) {
        var booking = db.one("SELECT * FROM bookings WHERE id=?", id);
        var line = db.bookingRoom(id, lineId);
        db.require(line.s("status").equals(checkIn ? "CONFIRMED" : "CHECKED_IN"), "state");
        var assigned = bookings.activeAllocation(lineId);
        var now = Instant.now();
        var balance = ledger.balance(id);
        var surcharge = surcharge(booking, line, now, checkIn);
        BigDecimal credit = ZERO, fee = ZERO;
        if (!checkIn) {
            LocalDate today = db.today();
            LocalDate cutoff =
                    today.isAfter(assigned.date("from_date"))
                            ? today
                            : assigned.date("from_date").plusDays(1);
            var unused =
                    bookings.nights(lineId).stream()
                            .filter(n -> !n.date("stay_date").isBefore(cutoff))
                            .toList();
            for (var n : unused)
                credit =
                        credit.add(
                                db.sum(
                                        "SELECT COALESCE(SUM(amount),0) FROM booking_charges WHERE"
                                                + " booking_room_night_id=? AND status='POSTED'",
                                        n.id("id")));
            fee = BookingService.unusedNightsFee(bookings.policy(booking), unused, now);
        }
        var projected =
                balance.debt()
                        .subtract(credit)
                        .add(fee)
                        .add(surcharge == null ? ZERO : surcharge.total());
        var required = booking.money("deposit_required_amount").min(balance.total().max(ZERO));
        boolean finalRoom =
                bookings.lines(id).stream()
                        .noneMatch(
                                l ->
                                        l.id("id") != lineId
                                                && Set.of("PENDING", "CONFIRMED", "CHECKED_IN")
                                                        .contains(l.s("status")));
        return new StayPreview(
                balance.debt(),
                projected,
                credit,
                fee,
                required.subtract(balance.net()).max(ZERO),
                surcharge,
                line.n("adults"),
                line.n("children"),
                line.s("status"),
                db.one("SELECT status FROM rooms WHERE id=?", assigned.id("room_id")).s("status"),
                finalRoom);
    }
}
