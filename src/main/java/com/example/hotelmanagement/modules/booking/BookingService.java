package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;
import static com.example.hotelmanagement.modules.pricing.dto.PricingModels.*;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.allocation.RoomAllocationService.Window;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.payment.BookingLedger;
import com.example.hotelmanagement.modules.policy.entity.FeeType;
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
public class BookingService {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;
    private final RoomAllocationService allocation;
    private final RoomPricingEngineService pricing;
    private final BookingLedger ledger;

    public record RoomInput(long typeId, LocalDate from, LocalDate to, int adults, int children) {
        public Window window() {
            return new Window(typeId, from, to);
        }
    }

    public record Create(
            String requestKey,
            Long customerId,
            String name,
            String phone,
            String email,
            String source,
            String voucher,
            String notes,
            List<RoomInput> rooms) {}

    @PreAuthorize("hasAuthority('booking.create')")
    @Transactional(readOnly = true)
    public Quote preview(Create input) {
        return price(input.rooms(), input.voucher(), input.customerId());
    }

    @PreAuthorize("hasAuthority('booking.create')")
    public long create(Create input, BigDecimal expectedTotal) {
        db.require("PHONE".equals(input.source()) || "WALK_IN".equals(input.source()), "input");
        inventory.lock();
        db.require(
                input.requestKey() != null
                        && input.requestKey().matches("[A-F0-9]{24}")
                        && db.actor() != null,
                "input");
        String code = "BK" + input.requestKey();
        var existing = db.rows("SELECT id,created_by FROM bookings WHERE booking_code=?", code);
        if (!existing.isEmpty()) {
            db.require(Objects.equals(existing.get(0).id("created_by"), db.actor()), "input");
            return existing.get(0).id("id");
        }
        return createLocked(input, expectedTotal, code, false);
    }

    long createLocked(Create input, BigDecimal expectedTotal, String code, boolean online) {
        var customer =
                input.customerId() == null
                        ? null
                        : db.one("SELECT * FROM customers WHERE id=?", input.customerId());
        String name = db.required(fallback(input.name(), customer, "full_name"), 150);
        String phone = db.required(fallback(input.phone(), customer, "phone"), 20);
        String email = db.optional(fallback(input.email(), customer, "email"), 150);
        long customerId =
                customer == null
                        ? db.insert(
                                "customers",
                                "customer_code,full_name,phone,email",
                                code("C"),
                                name,
                                phone,
                                email)
                        : customer.id("id");
        String voucher = db.optional(input.voucher(), 50);
        Long voucherId =
                voucher == null
                        ? null
                        : db.one(
                                        "SELECT id FROM vouchers WHERE UPPER(code)=UPPER(?) FOR"
                                                + " UPDATE",
                                        voucher)
                                .id("id");
        Quote quote = price(input.rooms(), voucher, customerId);
        boolean samePrice =
                expectedTotal != null && expectedTotal.compareTo(quote.totalAmount()) == 0;
        if (online && !samePrice)
            throw new BusinessException(
                    ErrorCode.BOOKING_CONFLICT,
                    "Giá phòng đã thay đổi. Vui lòng kiểm tra lại báo giá.");
        db.require(samePrice, "priceChanged");
        var policy = quote.policies();
        var zone = ZoneId.of(policy.timezone());
        Instant expires =
                quote.evaluationTime()
                        .plus(Duration.ofMinutes(db.settings().n("booking_hold_minutes")));
        if (quote.depositDueAt() != null && quote.depositDueAt().isBefore(expires))
            expires = quote.depositDueAt();
        var windows = input.rooms().stream().map(RoomInput::window).toList();
        allocation.lockInventory(windows);
        var assigned = new ArrayList<Long>();
        for (int i = 0; i < windows.size(); i++) {
            var window = windows.get(i);
            Set<Long> overlapping = new HashSet<>();
            for (int j = 0; j < i; j++) {
                var earlier = windows.get(j);
                if (earlier.from().isBefore(window.to()) && window.from().isBefore(earlier.to()))
                    overlapping.add(assigned.get(j));
            }
            assigned.add(allocation.choose(window, null, null, overlapping));
        }
        allocation.lockRooms(assigned);
        long id =
                db.insert(
                        "bookings",
                        "booking_code,customer_id,created_by,source,contact_name,contact_email,contact_phone,"
                            + "special_requests,deposit_policy_id,cancellation_policy_id,policy_snapshot,currency,deposit_required_amount,deposit_due_at,hold_expires_at",
                        code,
                        customerId,
                        db.actor(),
                        input.source(),
                        name,
                        email,
                        phone,
                        db.optional(input.notes(), 1000),
                        policy.deposit().id(),
                        policy.cancellation().id(),
                        db.json(policy),
                        policy.currency(),
                        quote.depositRequired(),
                        ts(quote.depositDueAt() == null ? null : expires),
                        ts(expires));
        for (int i = 0; i < input.rooms().size(); i++) {
            var room = input.rooms().get(i);
            int lineNumber = i + 1;
            var nights = quote.nights().stream().filter(n -> n.line() == lineNumber).toList();
            long line =
                    db.insert(
                            "booking_rooms",
                            "booking_id,line_number,room_type_id,room_type_name_snapshot,check_in_date,check_out_date,"
                                + "expected_check_in_at,expected_check_out_at,adults,children",
                            id,
                            lineNumber,
                            room.typeId(),
                            nights.get(0).roomTypeName(),
                            room.from(),
                            room.to(),
                            ts(at(room.from(), policy.checkIn(), zone)),
                            ts(at(room.to(), policy.checkOut(), zone)),
                            room.adults(),
                            room.children());
            allocation.schedule(line, assigned.get(i), room.from(), room.to(), null);
            for (var night : nights) postNight(id, line, night, "HELD", policy.taxPercent(), null);
            history(id, line, null, "PENDING", null);
        }
        if (voucherId != null) reserveVoucher(id, customerId, voucherId, quote);
        history(id, null, null, "PENDING", null);
        db.event("BOOKING:" + id + ":CREATED", "BOOKING_CREATED", id);
        db.audit(
                "BOOKING_CREATE",
                "BOOKING",
                id,
                Map.of(),
                Map.of("code", code, "rooms", quote.roomCount()),
                null);
        return id;
    }

    void reserveVoucher(long id, long customerId, long voucherId, Quote quote) {
        String snapshot =
                db.json(
                        Map.of(
                                "code",
                                quote.voucherCode(),
                                "discount",
                                quote.voucherDiscountTotal(),
                                "releaseOnFullCancellationBeforeStay",
                                true));
        if (db.update(
                        """
                UPDATE voucher_redemptions SET voucher_id=?, discount_amount=?, voucher_snapshot=?,
                    status='RESERVED', reserved_at=CURRENT_TIMESTAMP(6), released_at=NULL, redeemed_at=NULL
                WHERE booking_id=? AND status='RELEASED'
                """,
                        voucherId,
                        quote.voucherDiscountTotal(),
                        snapshot,
                        id)
                == 0)
            db.insert(
                    "voucher_redemptions",
                    "voucher_id,booking_id,customer_id,discount_amount,voucher_snapshot",
                    voucherId,
                    id,
                    customerId,
                    quote.voucherDiscountTotal(),
                    snapshot);
    }

    Quote price(List<RoomInput> rooms, String voucher, Long customer) {
        return price(rooms, voucher, customer, null);
    }

    Quote price(List<RoomInput> rooms, String voucher, Long customer, Hotel frozenPolicy) {
        db.require(rooms != null && !rooms.isEmpty() && rooms.size() <= 12, "input");
        for (var room : rooms) {
            validateWindow(room.window());
            capacity(room.typeId(), room.adults(), room.children());
        }
        var request =
                new Request(
                        rooms.stream().map(r -> new Stay(r.typeId(), r.from(), r.to(), 1)).toList(),
                        voucher,
                        customer,
                        Instant.now(),
                        null);
        return frozenPolicy == null
                ? pricing.quote(request)
                : pricing.quoteWithPolicy(request, frozenPolicy);
    }

    void validateWindow(Window window) {
        LocalDate today = inventory.today();
        db.require(
                window.from() != null
                        && window.to() != null
                        && !window.from().isBefore(today)
                        && window.to().isAfter(window.from())
                        && !window.to().isAfter(inventory.horizon())
                        && !window.from().plusDays(366).isBefore(window.to()),
                "dates");
    }

    void capacity(long type, int adults, int children) {
        var room = db.one("SELECT * FROM room_types WHERE id=? AND is_active=TRUE", type);
        db.require(
                adults > 0
                        && adults <= room.n("max_adults")
                        && children >= 0
                        && children <= room.n("max_children")
                        && (long) adults + children <= room.n("max_occupancy"),
                "guests");
    }

    static Instant at(LocalDate date, LocalTime time, ZoneId zone) {
        return date.atTime(time).atZone(zone).toInstant();
    }

    private String fallback(String value, Row customer, String column) {
        return value == null || value.isBlank()
                ? customer == null ? null : customer.s(column)
                : value;
    }

    long postNight(
            long booking, long room, Night night, String state, BigDecimal taxRate, Long adjusts) {
        long revision =
                db.count(
                                "SELECT COALESCE(MAX(revision),0) FROM booking_room_nights WHERE"
                                        + " booking_room_id=? AND stay_date=?",
                                room,
                                night.date())
                        + 1;
        long id =
                db.insert(
                        "booking_room_nights",
                        "booking_room_id,stay_date,revision,inventory_room_type_id,rate_rule_id,length_discount_rule_id,"
                            + "base_price_snapshot,nightly_rate,length_discount_amount,voucher_discount_amount,net_room_amount,inventory_state",
                        room,
                        night.date(),
                        revision,
                        night.roomTypeId(),
                        night.overrideRule() == null ? null : night.overrideRule().id(),
                        night.lengthRule() == null ? null : night.lengthRule().id(),
                        night.basePriceSnapshot(),
                        night.nightlyRate(),
                        night.lengthDiscountAmount(),
                        night.voucherDiscountAmount(),
                        night.netRoomAmount(),
                        state);
        allocation.counters(
                night.roomTypeId(),
                night.date(),
                state.equals("HELD") ? 1 : 0,
                state.equals("RESERVED") ? 1 : 0);
        ledger.charge(
                booking,
                room,
                id,
                null,
                null,
                null,
                adjusts,
                "ROOM:" + id,
                adjusts == null ? "ROOM" : "ROOM_ADJUSTMENT",
                night.roomTypeName(),
                night.date(),
                BigDecimal.ONE,
                night.netRoomAmount(),
                taxRate);
        BigDecimal tax = percent(night.netRoomAmount(), taxRate);
        if (tax.signum() > 0)
            ledger.charge(
                    booking,
                    room,
                    id,
                    null,
                    null,
                    null,
                    null,
                    "ROOM-TAX:" + id,
                    "TAX",
                    "VAT · " + night.roomTypeName(),
                    night.date(),
                    BigDecimal.ONE,
                    tax,
                    taxRate);
        return id;
    }

    @PreAuthorize("hasAuthority('booking.confirm')")
    public void confirm(long id, String reason) {
        confirmHeld(lock(id), reason, db.actor());
    }

    void confirmHeld(Row booking, String reason, Long staffApprover) {
        long id = booking.id("id");
        if ("CONFIRMED".equals(booking.s("status"))) return;
        reason = db.required(reason, 1000);
        db.require(
                "PENDING".equals(booking.s("status"))
                        && booking.time("hold_expires_at") != null
                        && Instant.now().isBefore(booking.time("hold_expires_at")),
                "state");
        requireDeposit(booking);
        var pending = lines(id).stream().filter(l -> l.s("status").equals("PENDING")).toList();
        db.require(
                pending.stream().noneMatch(l -> l.date("check_in_date").isBefore(db.today())),
                "arrival");
        allocation.lockInventory(pending.stream().map(this::window).toList());
        var roomIds =
                pending.stream().map(l -> activeAllocation(l.id("id")).id("room_id")).toList();
        allocation.lockRooms(roomIds);
        for (var line : pending) {
            allocation.assertClaims(line.id("id"), window(line));
            for (var night : nights(line.id("id"))) {
                allocation.counters(
                        night.id("inventory_room_type_id"), night.date("stay_date"), -1, 1);
                db.update(
                        "UPDATE booking_room_nights SET inventory_state='RESERVED' WHERE id=?",
                        night.id("id"));
            }
            setLine(id, line, "CONFIRMED", reason);
        }
        db.update(
                "UPDATE bookings SET confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                staffApprover,
                id);
        closePendingOnlinePayments(id);
        db.update(
                "UPDATE voucher_redemptions SET status='REDEEMED',redeemed_at=CURRENT_TIMESTAMP(6)"
                        + " WHERE booking_id=? AND status='RESERVED'",
                id);
        aggregate(booking, reason);
        db.event("BOOKING:" + id + ":CONFIRMED", "BOOKING_CONFIRMED", id);
        db.audit(
                "BOOKING_CONFIRM",
                "BOOKING",
                id,
                Map.of("status", "PENDING"),
                Map.of("status", "CONFIRMED"),
                reason);
    }

    @PreAuthorize("hasAuthority('booking.confirm')")
    public void reject(long id, String reason) {
        terminate(id, null, "REJECTED", db.required(reason, 1000));
    }

    @PreAuthorize("hasAuthority('booking.cancel.any')")
    public void cancel(long id, Long line, String reason) {
        terminate(id, line, "CANCEL", db.required(reason, 1000));
    }

    public record NoShowReview(
            BigDecimal roomAmount,
            BigDecimal fee,
            BigDecimal projectedDebt,
            BigDecimal refundDue) {}

    @PreAuthorize("hasAuthority('booking.no_show')")
    @Transactional(readOnly = true)
    public NoShowReview previewNoShow(long id, long lineId) {
        var br = db.bookingRoom(id, lineId);
        db.require(
                "CONFIRMED".equals(br.s("status"))
                        && !Instant.now().isBefore(br.time("expected_check_in_at")),
                "state");
        var order = db.one("SELECT * FROM bookings WHERE id=?", id);
        var nights = nights(lineId);
        var amount =
                nights.stream().map(n -> n.money("net_room_amount")).reduce(ZERO, BigDecimal::add);
        var fee =
                cancellationFee(
                        policy(order).cancellation(),
                        br,
                        Instant.now(),
                        amount,
                        nights.isEmpty() ? ZERO : nights.get(0).money("net_room_amount"),
                        true);
        var credit = ZERO;
        for (var n : nights)
            credit =
                    credit.add(
                            db.sum(
                                    "SELECT COALESCE(SUM(amount),0) FROM booking_charges WHERE"
                                            + " booking_room_night_id=? AND status='POSTED'",
                                    n.id("id")));
        credit =
                credit.add(
                        db.sum(
                                "SELECT COALESCE(SUM(amount),0) FROM booking_charges WHERE"
                                        + " booking_id=? AND booking_room_id=? AND source_key LIKE"
                                        + " 'GUEST-ADDON:%' AND status='POSTED'",
                                id, lineId));
        var debt = ledger.balance(id).debt().subtract(credit).add(fee);
        return new NoShowReview(amount, fee, debt, debt.negate().max(ZERO));
    }

    @PreAuthorize("hasAuthority('booking.no_show')")
    public void noShow(long id, long line, String reason) {
        terminate(id, line, "NO_SHOW", db.required(reason, 1000));
    }

    public void expire(long id) {
        terminate(id, null, "EXPIRED", "Hết hạn giữ chỗ / Hold expired");
    }

    void closePendingOnlinePayments(long id) {
        db.update(
                """
                UPDATE payment_transactions SET status='CANCELLED', updated_at=CURRENT_TIMESTAMP(6)
                WHERE booking_id=? AND provider='SANDBOX' AND status='PENDING'
                """,
                id);
    }

    void terminate(long id, Long lineId, String action, String reason) {
        var booking = lock(id);
        Instant now = Instant.now();
        if (action.equals("EXPIRED")
                && (!"PENDING".equals(booking.s("status"))
                        || booking.time("hold_expires_at") == null
                        || now.isBefore(booking.time("hold_expires_at")))) return;
        var all = lines(id);
        if (lineId == null)
            db.require(all.stream().noneMatch(l -> l.s("status").equals("CHECKED_IN")), "state");
        else db.require(!db.bookingRoom(id, lineId).s("status").equals("CHECKED_IN"), "state");
        var selected =
                all.stream()
                        .filter(l -> lineId == null || lineId.equals(l.id("id")))
                        .filter(l -> Set.of("PENDING", "CONFIRMED").contains(l.s("status")))
                        .toList();
        if (selected.isEmpty()) return;
        ledger.editable(id);
        for (var line : selected) {
            if (action.equals("NO_SHOW"))
                db.require(
                        line.s("status").equals("CONFIRMED")
                                && !now.isBefore(line.time("expected_check_in_at")),
                        "state");
            if (action.equals("REJECTED")) db.require(line.s("status").equals("PENDING"), "state");
        }
        allocation.lockInventory(selected.stream().map(this::window).toList());
        var allocations = selected.stream().map(l -> activeAllocation(l.id("id"))).toList();
        allocation.lockRooms(allocations.stream().map(a -> a.id("room_id")).toList());
        Hotel policy = policy(booking);
        BigDecimal subtotal = ZERO, fee = ZERO;
        for (var line : selected) {
            var nights = nights(line.id("id"));
            BigDecimal amount =
                    nights.stream()
                            .map(n -> n.money("net_room_amount"))
                            .reduce(ZERO, BigDecimal::add);
            subtotal = subtotal.add(amount);
            if (action.equals("NO_SHOW")
                    || action.equals("CANCEL") && line.s("status").equals("CONFIRMED")) {
                fee =
                        fee.add(
                                cancellationFee(
                                        policy.cancellation(),
                                        line,
                                        now,
                                        amount,
                                        nights.isEmpty()
                                                ? ZERO
                                                : nights.get(0).money("net_room_amount"),
                                        action.equals("NO_SHOW")));
            }
        }
        Long cancellation =
                action.equals("REJECTED")
                        ? null
                        : db.insert(
                                "booking_cancellations",
                                "booking_id,booking_room_id,kind,requested_by,processed_by,reason,policy_snapshot,cancelled_room_amount,cancellation_fee",
                                id,
                                lineId,
                                action,
                                db.actor(),
                                db.actor(),
                                reason,
                                db.json(policy.cancellation()),
                                subtotal,
                                fee);
        String status =
                switch (action) {
                    case "NO_SHOW", "REJECTED" -> action;
                    default -> "CANCELLED";
                };
        for (int i = 0; i < selected.size(); i++) {
            var line = selected.get(i);
            var assigned = allocations.get(i);
            long lineKey = line.id("id");
            // Unconsumed extra beds/guest add-ons end with a room cancelled before check-in.
            db.update(
                    "UPDATE booking_charges SET"
                        + " status='VOIDED',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason=?"
                        + " WHERE booking_id=? AND booking_room_id=? AND source_key LIKE"
                        + " 'GUEST-ADDON:%' AND status='POSTED'",
                    db.actor(), reason.substring(0, Math.min(reason.length(), 500)), id, lineKey);
            allocation.releaseClaims(assigned.id("id"), line.date("check_in_date"));
            db.update(
                    "UPDATE room_allocations SET status='RELEASED',ended_by=?,reason=? WHERE id=?",
                    db.actor(),
                    reason.substring(0, Math.min(reason.length(), 500)),
                    assigned.id("id"));
            for (var night : nights(lineKey))
                releaseNight(
                        id,
                        lineKey,
                        night,
                        cancellation,
                        "END",
                        action.equals("NO_SHOW") ? "NO_SHOW" : "CANCELLED");
            setLine(id, line, status, reason);
        }
        if (fee.signum() > 0)
            ledger.charge(
                    id,
                    lineId,
                    null,
                    null,
                    cancellation,
                    null,
                    null,
                    "CANCEL:" + cancellation,
                    "CANCELLATION",
                    "Phí hủy / Cancellation fee",
                    db.today(),
                    BigDecimal.ONE,
                    fee,
                    null);
        if (cancellation != null)
            db.update(
                    "UPDATE booking_cancellations SET proposed_refund_amount=? WHERE id=?",
                    ledger.balance(id).refundDue(),
                    cancellation);
        if (action.equals("REJECTED"))
            db.update(
                    "UPDATE bookings SET"
                        + " rejected_by=?,rejected_at=CURRENT_TIMESTAMP(6),rejection_reason=? WHERE"
                        + " id=?",
                    db.actor(),
                    reason,
                    id);
        if (lines(id).stream()
                .noneMatch(
                        l ->
                                Set.of("PENDING", "CONFIRMED", "CHECKED_IN", "CHECKED_OUT")
                                        .contains(l.s("status")))) {
            closePendingOnlinePayments(id);
            db.update(
                    "UPDATE booking_qr_tokens SET revoked_at=CURRENT_TIMESTAMP(6) WHERE"
                        + " booking_id=? AND revoked_at IS NULL",
                    id);
            db.update(
                    "UPDATE voucher_redemptions SET"
                        + " status='RELEASED',released_at=CURRENT_TIMESTAMP(6) WHERE booking_id=?"
                        + " AND status<>'RELEASED'",
                    id);
        }
        aggregate(booking, reason);
        db.event(
                "BOOKING:" + id + ":" + action + ":" + selected.get(0).id("id"),
                "BOOKING_" + status,
                id);
        db.audit(
                "BOOKING_" + action,
                "BOOKING",
                id,
                Map.of("status", booking.s("status")),
                Map.of("roomStatus", status, "fee", fee),
                reason);
    }

    static BigDecimal cancellationFee(
            Cancellation policy,
            Row line,
            Instant now,
            BigDecimal amount,
            BigDecimal first,
            boolean noShow) {
        Instant expected = line.time("expected_check_in_at");
        if (noShow || now.isAfter(expected))
            return fee(policy.noShowFeeType(), policy.noShowFeeValue(), amount, first);
        var tier =
                policy.tiers().stream()
                        .filter(
                                t ->
                                        !now.isAfter(
                                                expected.minus(
                                                        Duration.ofHours(
                                                                t.minHoursBeforeCheckin()))))
                        .max(Comparator.comparingInt(Tier::minHoursBeforeCheckin))
                        .orElseThrow();
        return fee(tier.feeType(), tier.feeValue(), amount, first);
    }

    static BigDecimal unusedNightsFee(Hotel policy, List<Row> nights, Instant now) {
        if (nights.isEmpty()) return ZERO;
        var first = nights.get(0);
        var expected = at(first.date("stay_date"), policy.checkIn(), ZoneId.of(policy.timezone()));
        var clock = new Row(Map.of("expected_check_in_at", ts(expected)));
        var subtotal =
                nights.stream().map(n -> n.money("net_room_amount")).reduce(ZERO, BigDecimal::add);
        return cancellationFee(
                policy.cancellation(), clock, now, subtotal, first.money("net_room_amount"), false);
    }

    private static BigDecimal fee(
            FeeType type, BigDecimal value, BigDecimal amount, BigDecimal first) {
        return (switch (type) {
                    case NONE -> ZERO;
                    case FIXED -> vnd(value);
                    case PERCENT -> percent(amount, value);
                    case FIRST_NIGHT -> first;
                })
                .max(ZERO)
                .min(amount);
    }

    Long releaseNight(
            long booking, long line, Row night, Long cancellation, String key, String status) {
        boolean held = night.s("inventory_state").equals("HELD");
        allocation.counters(
                night.id("inventory_room_type_id"),
                night.date("stay_date"),
                held ? -1 : 0,
                held ? 0 : -1);
        db.update(
                "UPDATE booking_room_nights SET inventory_state='RELEASED',stay_status=? WHERE"
                        + " id=?",
                status,
                night.id("id"));
        return ledger.reverseNight(booking, line, night.id("id"), cancellation, key);
    }

    void requireDeposit(Row booking) {
        var balance = ledger.balance(booking.id("id"));
        var required = booking.money("deposit_required_amount").min(balance.total().max(ZERO));
        db.require(balance.net().compareTo(required) >= 0, "deposit");
    }

    Row lock(long id) {
        inventory.lock();
        db.rows(
                "SELECT v.id FROM vouchers v JOIN voucher_redemptions r ON r.voucher_id=v.id WHERE"
                        + " r.booking_id=? FOR UPDATE",
                id);
        return db.lockBooking(id);
    }

    List<Row> lines(long booking) {
        return db.rows(
                "SELECT * FROM booking_rooms WHERE booking_id=? ORDER BY line_number", booking);
    }

    List<Row> nights(long line) {
        return db.rows(
                "SELECT * FROM booking_room_nights WHERE booking_room_id=? AND"
                        + " inventory_state<>'RELEASED' ORDER BY stay_date",
                line);
    }

    Row activeAllocation(long line) {
        return db.one(
                "SELECT * FROM room_allocations WHERE booking_room_id=? AND status IN"
                        + " ('SCHEDULED','OCCUPIED')",
                line);
    }

    Window window(Row line) {
        return new Window(
                line.id("room_type_id"), line.date("check_in_date"), line.date("check_out_date"));
    }

    Hotel policy(Row booking) {
        return db.decode(booking.s("policy_snapshot"), Hotel.class);
    }

    void history(long booking, Long line, String before, String after, String reason) {
        db.insert(
                "booking_status_history",
                "booking_id,booking_room_id,old_status,new_status,changed_by,reason",
                booking,
                line,
                before,
                after,
                db.actor(),
                reason);
    }

    void setLine(long id, Row line, String status, String reason) {
        db.update(
                "UPDATE booking_rooms SET status=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                status,
                line.id("id"));
        history(id, line.id("id"), line.s("status"), status, reason);
    }

    void aggregate(Row booking, String reason) {
        long id = booking.id("id");
        Set<String> statuses = new HashSet<>();
        lines(id).forEach(l -> statuses.add(l.s("status")));
        for (String status :
                List.of(
                        "CHECKED_IN",
                        "CONFIRMED",
                        "PENDING",
                        "CHECKED_OUT",
                        "NO_SHOW",
                        "CANCELLED",
                        "REJECTED")) {
            if (!statuses.contains(status)) continue;
            db.update(
                    "UPDATE bookings SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6)"
                            + " WHERE id=?",
                    status,
                    id);
            if (!status.equals(booking.s("status")))
                history(id, null, booking.s("status"), status, reason);
            return;
        }
    }
}
