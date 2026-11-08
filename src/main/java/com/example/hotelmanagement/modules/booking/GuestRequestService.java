package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.payment.BookingLedger;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GuestRequestService {
    private final OperationsStore db;
    private final BookingLedger ledger;
    private final BookingService bookings;
    public static final List<String> TAGS =
            List.of(
                    "QUIET_ROOM",
                    "HIGH_FLOOR",
                    "LOW_FLOOR",
                    "NON_SMOKING",
                    "CONNECTING_ROOM",
                    "LATE_ARRIVAL",
                    "EARLY_CHECKIN",
                    "LATE_CHECKOUT",
                    "BABY_COT",
                    "SOFT_PILLOWS",
                    "IRON");

    public record Addon(
            String kind,
            long policy,
            String name,
            int quantity,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal taxRate) {}

    public record Review(int revision, List<Addon> addons, BigDecimal total, String token) {}

    public record Form(
            long line,
            int revision,
            List<String> tags,
            int beds,
            int guests,
            String lineNotes,
            String special,
            String internal,
            String reason,
            String token) {}

    @PreAuthorize("hasAuthority('booking.read.any')")
    @Transactional(readOnly = true)
    public List<Row> preferences(long booking) {
        return db.rows(
                "SELECT br.id,br.line_number,br.special_requests,COALESCE(p.tags,'') AS"
                    + " tags,COALESCE(p.extra_beds,0) AS extra_beds,COALESCE(p.extra_guests,0) AS"
                    + " extra_guests,COALESCE(p.revision,0) AS revision FROM booking_rooms br LEFT"
                    + " JOIN booking_room_preferences p ON p.booking_room_id=br.id WHERE"
                    + " br.booking_id=? ORDER BY br.line_number",
                booking);
    }

    @PreAuthorize("hasAuthority('booking.update.any')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Review preview(long booking, long line, int beds, int guests) {
        var b = db.one("SELECT * FROM bookings WHERE id=?", booking);
        var r = db.bookingRoom(booking, line);
        db.require(Set.of("PENDING", "CONFIRMED", "CHECKED_IN").contains(r.s("status")), "state");
        db.require(beds >= 0 && beds <= 10 && guests >= 0 && guests <= 10, "input");
        int revision =
                db
                        .rows(
                                "SELECT revision FROM booking_room_preferences WHERE"
                                    + " booking_room_id=?",
                                line)
                        .stream()
                        .map(x -> x.n("revision"))
                        .findFirst()
                        .orElse(0);
        List<Addon> result = new ArrayList<>();
        addon(result, b, r, "EXTRA_BED", beds);
        addon(result, b, r, "EXTRA_GUEST", guests);
        BigDecimal total =
                result.stream().map(x -> x.amount().add(x.tax())).reduce(ZERO, BigDecimal::add);
        return new Review(
                revision,
                result,
                total,
                com.example.hotelmanagement.modules.reception.QrCodeSupport.hash(
                        db.json(List.of(b.n("version"), line, revision, beds, guests, result))));
    }

    private void addon(List<Addon> result, Row b, Row r, String kind, int quantity) {
        if (quantity == 0) return;
        var rules =
                db.rows(
                        "SELECT * FROM surcharge_policies WHERE is_active=TRUE AND kind=? AND"
                            + " (room_type_id IS NULL OR room_type_id=?) ORDER BY priority DESC,id"
                            + " DESC LIMIT 1",
                        kind,
                        r.id("room_type_id"));
        db.require(!rules.isEmpty(), "addonPolicy");
        var p = rules.get(0);
        db.require(!p.s("amount_type").equals("PER_HOUR"), "addonPolicy");
        BigDecimal base =
                bookings.nights(r.id("id")).stream()
                        .filter(
                                n ->
                                        !n.s("stay_status").equals("CANCELLED")
                                                && !n.s("stay_status").equals("NO_SHOW"))
                        .map(n -> n.money("net_room_amount"))
                        .reduce(ZERO, BigDecimal::add);
        BigDecimal amount =
                p.s("amount_type").equals("PERCENT_NIGHT")
                        ? percent(base, p.money("amount_value"))
                        : vnd(p.money("amount_value"));
        amount = amount.multiply(BigDecimal.valueOf(quantity));
        var rate = bookings.policy(b).taxPercent();
        result.add(
                new Addon(
                        kind,
                        p.id("id"),
                        p.s("name"),
                        quantity,
                        amount,
                        percent(amount, rate),
                        rate));
    }

    @PreAuthorize("hasAuthority('booking.update.any')")
    public void save(long booking, Form f) {
        var b = db.lockBooking(booking);
        ledger.editable(booking);
        var r = db.bookingRoom(booking, f.line());
        var review = preview(booking, f.line(), f.beds(), f.guests());
        db.require(
                f.revision() == review.revision() && Objects.equals(review.token(), f.token()),
                "priceChanged");
        var tags = new TreeSet<>(f.tags() == null ? List.<String>of() : f.tags());
        db.require(TAGS.containsAll(tags), "input");
        String reason = db.required(f.reason(), 500),
                special = db.optional(f.special(), 5000),
                internal = db.optional(f.internal(), 5000),
                lineNotes = db.optional(f.lineNotes(), 5000);
        // Add-on amount is per requested unit per stay; percent applies to active room-night net.
        db.update(
                "UPDATE booking_charges SET"
                    + " status='VOIDED',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason=?"
                    + " WHERE booking_id=? AND booking_room_id=? AND source_key LIKE"
                    + " 'GUEST-ADDON:%' AND status='POSTED'",
                db.actor(), reason, booking, f.line());
        for (var a : review.addons()) {
            String key = "GUEST-ADDON:" + f.line() + ":" + (review.revision() + 1) + ":" + a.kind();
            ledger.charge(
                    booking,
                    f.line(),
                    null,
                    null,
                    null,
                    a.policy(),
                    null,
                    key,
                    "SURCHARGE",
                    a.name() + " × " + a.quantity(),
                    db.today(),
                    BigDecimal.ONE,
                    a.amount(),
                    a.taxRate());
            if (a.tax().signum() > 0)
                ledger.charge(
                        booking,
                        f.line(),
                        null,
                        null,
                        null,
                        a.policy(),
                        null,
                        key + ":TAX",
                        "TAX",
                        "VAT · " + a.name(),
                        db.today(),
                        BigDecimal.ONE,
                        a.tax(),
                        a.taxRate());
        }
        if (review.revision() == 0
                && db.count(
                                "SELECT COUNT(*) FROM booking_room_preferences WHERE"
                                    + " booking_room_id=?",
                                f.line())
                        == 0)
            db.update(
                    "INSERT INTO"
                        + " booking_room_preferences(booking_room_id,booking_id,tags,extra_beds,extra_guests,revision)"
                        + " VALUES(?,?,?,?,?,?)",
                    f.line(),
                    booking,
                    String.join(",", tags),
                    f.beds(),
                    f.guests(),
                    1);
        else
            db.update(
                    "UPDATE booking_room_preferences SET"
                        + " tags=?,extra_beds=?,extra_guests=?,revision=revision+1 WHERE"
                        + " booking_room_id=?",
                    String.join(",", tags),
                    f.beds(),
                    f.guests(),
                    f.line());
        db.update(
                "UPDATE booking_rooms SET special_requests=?,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                    + " id=?",
                lineNotes,
                f.line());
        db.update(
                "UPDATE bookings SET"
                    + " special_requests=?,internal_notes=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6)"
                    + " WHERE id=?",
                special,
                internal,
                booking);
        db.audit(
                "BOOKING_GUEST_REQUESTS",
                "BOOKING",
                booking,
                Map.of("line", f.line()),
                Map.of(
                        "tags",
                        tags,
                        "beds",
                        f.beds(),
                        "guests",
                        f.guests(),
                        "surcharge",
                        review.total()),
                reason);
        db.event(code("GUEST-REQUEST:"), "BOOKING_SPECIAL_REQUESTS_CHANGED", booking);
    }
}
