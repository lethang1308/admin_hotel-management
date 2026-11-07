package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;
import static com.example.hotelmanagement.modules.pricing.dto.PricingModels.*;

import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.allocation.RoomAllocationService.Window;
import com.example.hotelmanagement.modules.payment.BookingLedger;
import com.example.hotelmanagement.modules.pricing.service.RoomPricingEngineService;

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
@PreAuthorize("hasAuthority('booking.update.any')")
public class BookingChangeService {
    private final OperationsStore db;
    private final RoomAllocationService allocation;
    private final BookingService bookings;
    private final StayService stays;
    private final BookingLedger ledger;
    private final RoomPricingEngineService pricing;

    public record Change(String kind, LocalDate from, LocalDate to, Long physicalRoom) {}

    public record Saved(Change change, BigDecimal difference, BigDecimal fee, int bookingVersion) {}

    private record Plan(
            Row line,
            Row old,
            boolean occupied,
            long target,
            Window window,
            LocalDate claimFrom,
            List<Row> removed,
            List<Night> added,
            BigDecimal fee,
            BigDecimal difference) {}

    public long request(long bookingId, long lineId, Change change, String reason) {
        var booking = bookings.lock(bookingId);
        reason = db.required(reason, 500);
        var p = plan(booking, lineId, change);
        long id =
                db.insert(
                        "booking_change_requests",
                        "booking_id,booking_room_id,requested_by,change_type,before_snapshot,requested_changes,reason",
                        bookingId,
                        lineId,
                        db.actor(),
                        change.kind(),
                        db.json(
                                Map.of(
                                        "roomId",
                                        p.old().id("room_id"),
                                        "from",
                                        p.line().date("check_in_date"),
                                        "to",
                                        p.line().date("check_out_date"),
                                        "typeId",
                                        p.line().id("room_type_id"))),
                        db.json(new Saved(change, p.difference(), p.fee(), booking.n("version"))),
                        reason);
        db.audit(
                "BOOKING_CHANGE_REQUEST",
                "BOOKING",
                bookingId,
                Map.of(),
                Map.of("request", id, "difference", p.difference()),
                reason);
        db.event("CHANGE-REQUEST:" + id, "BOOKING_CHANGE_REQUESTED", bookingId);
        return id;
    }

    public void decide(long bookingId, long requestId, boolean approve, String note) {
        var booking = bookings.lock(bookingId);
        var request =
                db.one(
                        "SELECT * FROM booking_change_requests WHERE id=? AND booking_id=? FOR"
                                + " UPDATE",
                        requestId,
                        bookingId);
        if (!"PENDING".equals(request.s("status"))) return;
        note = db.required(note, 500);
        if (approve) {
            var saved = db.decode(request.s("requested_changes"), Saved.class);
            db.require(saved.bookingVersion() == booking.n("version"), "stale");
            var p = plan(booking, request.id("booking_room_id"), saved.change());
            db.require(
                    saved.difference().compareTo(p.difference()) == 0
                            && saved.fee().compareTo(p.fee()) == 0,
                    "priceChanged");
            apply(booking, requestId, p, note);
        }
        String status = approve ? "APPROVED" : "REJECTED";
        db.update(
                "UPDATE booking_change_requests SET"
                    + " status=?,handled_by=?,handled_at=CURRENT_TIMESTAMP(6),response_note=? WHERE"
                    + " id=?",
                status,
                db.actor(),
                note,
                requestId);
        db.audit(
                "BOOKING_CHANGE_" + status,
                "BOOKING",
                bookingId,
                Map.of(),
                Map.of("request", requestId),
                note);
    }

    private Plan plan(Row booking, long lineId, Change change) {
        long id = booking.id("id");
        ledger.editable(id);
        var line = db.bookingRoom(id, lineId);
        boolean occupied = "CHECKED_IN".equals(line.s("status"));
        db.require(occupied || "CONFIRMED".equals(line.s("status")), "state");
        var old = bookings.activeAllocation(lineId);
        var original = bookings.window(line);
        boolean move = "CHANGE_ROOM".equals(change.kind());
        LocalDate from = change.from() == null ? original.from() : change.from();
        LocalDate to = change.to() == null ? original.to() : change.to();
        boolean sameArrival = original.from().equals(from);
        db.require(
                switch (change.kind()) {
                    case "CHANGE_ROOM" -> sameArrival
                            && original.to().equals(to)
                            && change.physicalRoom() != null
                            && !change.physicalRoom().equals(old.id("room_id"));
                    case "CHANGE_DATES" -> !occupied && (!sameArrival || !original.to().equals(to));
                    case "EXTEND" -> sameArrival && to.isAfter(original.to());
                    case "SHORTEN" -> sameArrival && to.isBefore(original.to());
                    default -> false;
                },
                "dates");
        LocalDate today = db.today();
        LocalDate claimFrom = occupied ? today : from;
        Long preferred =
                move
                        ? change.physicalRoom()
                        : occupied || !"CHANGE_DATES".equals(change.kind())
                                ? old.id("room_id")
                                : null;
        long type =
                move
                        ? db.one("SELECT room_type_id FROM rooms WHERE id=?", preferred)
                                .id("room_type_id")
                        : original.type();
        bookings.validateWindow(new Window(type, claimFrom, to));
        db.require(!from.plusDays(366).isBefore(to), "dates");
        bookings.capacity(type, line.n("adults"), line.n("children"));
        var window = new Window(type, from, to);
        long target =
                allocation.choose(
                        new Window(type, claimFrom, to), preferred, old.id("id"), Set.of());
        var active = bookings.nights(lineId);
        var removed =
                active.stream()
                        .filter(
                                n -> {
                                    var d = n.date("stay_date");
                                    return (!occupied || !d.isBefore(today))
                                            && (d.isBefore(from)
                                                    || !d.isBefore(to)
                                                    || n.id("inventory_room_type_id") != type);
                                })
                        .toList();
        var retained = new HashSet<LocalDate>();
        active.stream()
                .filter(n -> !removed.contains(n))
                .forEach(n -> retained.add(n.date("stay_date")));
        var added =
                claimFrom.datesUntil(to).allMatch(retained::contains)
                        ? List.<Night>of()
                        : pricing.quoteAdjustment(new Stay(type, from, to, 1)).nights().stream()
                                .filter(
                                        n ->
                                                !n.date().isBefore(claimFrom)
                                                        && !retained.contains(n.date()))
                                .toList();
        var policy = bookings.policy(booking);
        BigDecimal credit = ZERO;
        for (var n : removed) {
            credit =
                    credit.add(
                            db.sum(
                                    """
                    SELECT COALESCE(SUM(amount),0) FROM booking_charges WHERE booking_room_night_id=? AND status='POSTED'
                    """,
                                    n.id("id")));
        }
        BigDecimal cost =
                added.stream()
                        .map(
                                n ->
                                        n.netRoomAmount()
                                                .add(
                                                        percent(
                                                                n.netRoomAmount(),
                                                                policy.taxPercent())))
                        .reduce(ZERO, BigDecimal::add);
        // An upgrade replaces nights rather than cancelling that stay. Charge the policy only for
        // discarded dates.
        var discarded =
                removed.stream()
                        .filter(
                                n ->
                                        n.date("stay_date").isBefore(from)
                                                || !n.date("stay_date").isBefore(to))
                        .toList();
        BigDecimal fee = BookingService.unusedNightsFee(policy, discarded, Instant.now());
        return new Plan(
                line,
                old,
                occupied,
                target,
                window,
                claimFrom,
                removed,
                added,
                fee,
                cost.subtract(credit).add(fee));
    }

    private void apply(Row booking, long request, Plan p, String note) {
        long id = booking.id("id"),
                line = p.line().id("id"),
                oldId = p.old().id("id"),
                oldRoom = p.old().id("room_id");
        var windows = new ArrayList<Window>();
        for (var night : p.removed())
            windows.add(
                    new Window(
                            night.id("inventory_room_type_id"),
                            night.date("stay_date"),
                            night.date("stay_date").plusDays(1)));
        windows.add(new Window(p.window().type(), p.claimFrom(), p.window().to()));
        allocation.lockInventory(windows);
        allocation.lockRooms(List.of(oldRoom, p.target()));
        Instant now = Instant.now();
        var policy = bookings.policy(booking);
        var zone = ZoneId.of(policy.timezone());
        allocation.releaseClaims(oldId, p.occupied() ? p.claimFrom() : p.old().date("from_date"));
        if (p.occupied() && oldRoom == p.target()) {
            db.update("UPDATE room_allocations SET to_date=? WHERE id=?", p.window().to(), oldId);
            allocation.claim(oldId, p.target(), p.claimFrom(), p.window().to());
        } else {
            if (p.occupied()) {
                db.require(
                        "AVAILABLE"
                                .equals(
                                        db.one("SELECT status FROM rooms WHERE id=?", p.target())
                                                .s("status")),
                        "notReady");
                db.require(
                        db.count(
                                        """
                        SELECT COUNT(*) FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id
                        WHERE a.room_id=? AND (a.status='OCCUPIED' OR (a.status='SCHEDULED' AND a.to_date<=? AND br.expected_check_out_at>?))
                        """,
                                        p.target(),
                                        p.claimFrom(),
                                        ts(now))
                                == 0,
                        "notReady");
                if (p.claimFrom().isAfter(p.old().date("from_date")))
                    db.update(
                            "UPDATE room_allocations SET to_date=? WHERE id=?",
                            p.claimFrom(),
                            oldId);
            } else
                db.update(
                        "UPDATE room_allocations SET status='RELEASED',ended_by=? WHERE id=?",
                        db.actor(),
                        oldId);
            long assigned =
                    allocation.schedule(line, p.target(), p.claimFrom(), p.window().to(), note);
            if (p.occupied()) {
                db.update(
                        "UPDATE room_allocations SET status='OCCUPIED',occupied_from_at=? WHERE"
                                + " id=?",
                        ts(now),
                        assigned);
                db.update(
                        """
                        INSERT INTO stay_guests(booking_id,booking_room_id,booking_guest_id,room_allocation_id,arrived_at)
                        SELECT booking_id,booking_room_id,booking_guest_id,?,? FROM stay_guests WHERE room_allocation_id=? AND departed_at IS NULL
                        """,
                        assigned,
                        ts(now),
                        oldId);
                stays.endStay(oldId, now);
                allocation.turnOver(oldRoom, note);
                stays.occupyRoom(p.target(), note);
            }
        }
        Map<LocalDate, Long> adjustments = new HashMap<>();
        for (var night : p.removed())
            adjustments.put(
                    night.date("stay_date"),
                    bookings.releaseNight(id, line, night, null, "CHANGE:" + request, "CANCELLED"));
        for (var night : p.added())
            bookings.postNight(
                    id,
                    line,
                    night,
                    "RESERVED",
                    policy.taxPercent(),
                    adjustments.get(night.date()));
        if (p.fee().signum() > 0)
            ledger.charge(
                    id,
                    line,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "CHANGE-FEE:" + request,
                    "CANCELLATION",
                    "Phí đổi kỳ ở / Stay change fee",
                    db.today(),
                    BigDecimal.ONE,
                    p.fee(),
                    null);
        db.update(
                """
                UPDATE booking_rooms SET room_type_id=?,room_type_name_snapshot=?,check_in_date=?,check_out_date=?,
                expected_check_in_at=?,expected_check_out_at=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """,
                p.window().type(),
                db.one("SELECT name FROM room_types WHERE id=?", p.window().type()).s("name"),
                p.window().from(),
                p.window().to(),
                ts(BookingService.at(p.window().from(), policy.checkIn(), zone)),
                ts(BookingService.at(p.window().to(), policy.checkOut(), zone)),
                line);
        db.update(
                "UPDATE bookings SET version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                id);
        bookings.history(id, line, p.line().s("status"), p.line().s("status"), note);
        db.event("CHANGE:" + request, "BOOKING_CHANGED", id);
    }
}
