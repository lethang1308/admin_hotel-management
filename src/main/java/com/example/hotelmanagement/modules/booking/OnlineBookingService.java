package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.ErrorCode;
import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.booking.BookingService.Create;
import com.example.hotelmanagement.modules.booking.BookingService.RoomInput;
import com.example.hotelmanagement.modules.customer.service.IdentityDocumentCrypto;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.payment.BookingLedger;
import com.example.hotelmanagement.modules.pricing.dto.PricingModels.Quote;
import com.example.hotelmanagement.modules.reception.QrCodeSupport;
import com.fasterxml.jackson.annotation.JsonAlias;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OnlineBookingService {
    private static final String OWN = "b.customer_id IN (SELECT id FROM customers WHERE user_id=?)";
    private static final String QR_PREFIX = "HOTEL-BOOKING:";
    private final OperationsStore db;
    private final BookingService bookings;
    private final RoomAllocationService allocation;
    private final HotelInventoryCoordinator inventory;
    private final BookingLedger ledger;
    private final QrCodeSupport qr;
    private final IdentityDocumentCrypto documents;

    @Value("${app.booking.sandbox-enabled:false}")
    private boolean sandboxEnabled;

    public record Cart(
            List<RoomInput> rooms,
            String voucherCode,
            String requestKey,
            BigDecimal expectedTotal,
            Long hotelId,
            Long roomId,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            Integer roomsCount,
            Integer adults,
            Integer children,
            String contactName,
            String contactEmail,
            String contactPhone,
            String specialRequests) {}

    public record GuestInput(
            @JsonAlias("fullName") String name,
            LocalDate dateOfBirth,
            String gender,
            String guestType,
            String phone,
            String documentType,
            String documentNumber) {}

    private record GuestSnapshot(GuestInput guest, String type, String encrypted, String hash) {}

    public record GuestDetails(
            String contactName,
            String contactEmail,
            String contactPhone,
            String specialRequests,
            List<Long> guestIds,
            List<GuestInput> guests,
            List<Long> retainedGuestIds) {}

    public record PaymentRequest(
            String provider, BigDecimal expectedTotal, BigDecimal expectedDeposit) {}

    public record PaymentAction(Long transactionId, String action) {}

    public record Payment(
            Long transactionId,
            String status,
            BigDecimal amount,
            String currency,
            Instant expiresAt,
            String bookingStatus) {}

    public record Page<T>(
            List<T> content,
            long totalElements,
            int totalPages,
            int number,
            int size,
            boolean first,
            boolean last,
            boolean empty,
            int numberOfElements,
            Map<String, Long> stats) {}

    @PreAuthorize("hasAuthority('booking.create')")
    @Transactional(readOnly = true)
    public Quote quote(Cart input) {
        var rooms = cart(input);
        var quote =
                bookings.price(rooms, db.optional(input.voucherCode(), 50), customer().id("id"));
        available(rooms);
        return quote;
    }

    @PreAuthorize("hasAuthority('booking.create')")
    public Map<String, Object> init(Cart input) {
        String request = db.required(input.requestKey(), 64);
        db.require(request.matches("[A-Za-z0-9_-]{16,64}"), "input");
        inventory.lock();
        var c = customer();
        var existing =
                db.rows(
                        "SELECT * FROM bookings WHERE customer_id=? AND online_request_key=?",
                        c.id("id"),
                        request);
        if (!existing.isEmpty()) return view(existing.get(0), true);
        var rooms = cart(input);
        if (db.count(
                        """
                SELECT COUNT(*) FROM bookings WHERE customer_id=? AND source='ONLINE'
                AND status='PENDING' AND hold_expires_at>?
                """,
                        c.id("id"),
                        ts(Instant.now()))
                >= 3)
            throw conflict("Bạn đang giữ nhiều đơn. Vui lòng hoàn tất hoặc hủy đơn đã giữ.");
        var contact = contact(c, input.contactName(), input.contactEmail(), input.contactPhone());
        available(rooms);
        String prefix = "BK" + inventory.today().format(DateTimeFormatter.ofPattern("yyMMdd"));
        String code;
        do {
            code = prefix + code("").substring(0, 6);
        } while (db.count("SELECT COUNT(*) FROM bookings WHERE booking_code=?", code) > 0);
        long id =
                bookings.createLocked(
                        new Create(
                                null,
                                c.id("id"),
                                contact.name(),
                                contact.phone(),
                                contact.email(),
                                "ONLINE",
                                db.optional(input.voucherCode(), 50),
                                input.specialRequests(),
                                rooms),
                        input.expectedTotal(),
                        code,
                        true);
        db.update("UPDATE bookings SET online_request_key=? WHERE id=?", request, id);
        snapshot(id, contact, List.of());
        return view(owned(id), true);
    }

    private List<RoomInput> cart(Cart input) {
        db.require(input.hotelId() == null || input.hotelId() == 1, "input");
        var rooms = input.rooms();
        if (rooms == null) {
            db.require(
                    input.roomId() != null
                            && input.roomsCount() != null
                            && input.roomsCount() >= 1
                            && input.roomsCount() <= 5
                            && input.adults() != null
                            && input.adults() >= input.roomsCount()
                            && input.adults() <= 100
                            && (input.children() == null
                                    || input.children() >= 0 && input.children() <= 100),
                    "guests");
            int size = input.roomsCount(), kids = input.children() == null ? 0 : input.children();
            var expanded = new ArrayList<RoomInput>();
            for (int i = 0; i < size; i++)
                expanded.add(
                        new RoomInput(
                                input.roomId(),
                                input.checkInDate(),
                                input.checkOutDate(),
                                input.adults() / size + (i < input.adults() % size ? 1 : 0),
                                kids / size + (i < kids % size ? 1 : 0)));
            rooms = expanded;
        }
        db.require(
                !rooms.isEmpty() && rooms.size() <= 5 && rooms.stream().noneMatch(Objects::isNull),
                "input");
        var first = rooms.get(0);
        for (var r : rooms) {
            bookings.validateWindow(r.window());
            db.require(
                    r.from().equals(first.from())
                            && r.to().equals(first.to())
                            && !r.to().isAfter(r.from().plusDays(30)),
                    "dates");
            bookings.capacity(r.typeId(), r.adults(), r.children());
        }
        return rooms;
    }

    private void available(List<RoomInput> rooms) {
        var counts =
                rooms.stream()
                        .collect(Collectors.groupingBy(RoomInput::typeId, Collectors.counting()));
        var first = rooms.get(0);
        for (var entry : counts.entrySet()) {
            long type = entry.getKey(), count = entry.getValue();
            long stock =
                    db.count(
                            """
                    SELECT COUNT(*) FROM room_type_inventory WHERE room_type_id=? AND stay_date>=?
                    AND stay_date<? AND sellable_rooms-held_rooms-reserved_rooms>=?
                    """,
                            type,
                            first.from(),
                            first.to(),
                            count);
            if (stock != first.to().toEpochDay() - first.from().toEpochDay()
                    || allocation
                                    .candidates(
                                            new RoomAllocationService.Window(
                                                    type, first.from(), first.to()),
                                            null,
                                            false)
                                    .stream()
                                    .filter(RoomAllocationService.Candidate::available)
                                    .limit(count)
                                    .count()
                            < count)
                throw conflict(
                        "Không đủ phòng trống cho toàn bộ kỳ lưu trú. Vui lòng đổi ngày hoặc loại"
                                + " phòng.");
        }
    }

    @PreAuthorize("hasAuthority('booking.create')")
    public Map<String, Object> saveGuests(long id, GuestDetails input) {
        var b = lockPending(id);
        var contact = contact(b, input.contactName(), input.contactEmail(), input.contactPhone());
        var ids = input.guestIds() == null ? List.<Long>of() : input.guestIds();
        var direct = input.guests() == null ? List.<GuestInput>of() : input.guests();
        db.require(
                ids.size() <= 100
                        && direct.size() <= 100
                        && ids.stream().noneMatch(Objects::isNull)
                        && new HashSet<>(ids).size() == ids.size()
                        && direct.stream().noneMatch(Objects::isNull),
                "guests");
        List<GuestSnapshot> guests = new ArrayList<>();
        var retained =
                input.retainedGuestIds() == null ? List.<Long>of() : input.retainedGuestIds();
        db.require(
                retained.size() <= 100
                        && retained.stream().noneMatch(Objects::isNull)
                        && new HashSet<>(retained).size() == retained.size(),
                "guests");
        if (!retained.isEmpty()) {
            var snapshots =
                    db.rows("SELECT * FROM booking_guests WHERE booking_id=? ORDER BY id", id);
            var prior =
                    snapshots.stream().skip(1).collect(Collectors.toMap(g -> g.id("id"), g -> g));
            for (long key : retained) {
                var guest = prior.get(key);
                db.require(guest != null, "guests");
                guests.add(
                        new GuestSnapshot(
                                guestInput(guest),
                                guest.s("document_type"),
                                guest.s("document_number_encrypted"),
                                guest.s("document_number_hash")));
            }
        }
        for (long guestId : ids) {
            var g = saved(b.id("customer_id"), guestId);
            guests.add(
                    new GuestSnapshot(
                            guestInput(g),
                            g.s("document_type"),
                            g.s("document_number_encrypted"),
                            g.s("document_number_hash")));
        }
        direct.stream().map(this::cleanGuest).map(this::document).forEach(guests::add);
        var lines = bookings.lines(id);
        long adults = lines.stream().mapToLong(r -> r.n("adults")).sum();
        long kids = lines.stream().mapToLong(r -> r.n("children")).sum();
        long adultCompanions =
                guests.stream().filter(g -> "ADULT".equals(g.guest().guestType())).count();
        db.require(
                adultCompanions + 1 <= adults && guests.size() - adultCompanions <= kids, "guests");
        String notes =
                input.specialRequests() == null
                        ? b.s("special_requests")
                        : db.optional(input.specialRequests(), 1000);
        db.update(
                """
                UPDATE bookings SET contact_name=?,contact_email=?,contact_phone=?,special_requests=?,
                updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """,
                contact.name(),
                contact.email(),
                contact.phone(),
                notes,
                id);
        snapshot(id, contact, guests);
        db.audit(
                "ONLINE_BOOKING_GUESTS",
                "BOOKING",
                id,
                Map.of(),
                Map.of("guests", guests.size() + 1),
                null);
        return view(owned(id), true);
    }

    private void snapshot(long id, Contact contact, List<GuestSnapshot> guests) {
        db.update("DELETE FROM booking_guests WHERE booking_id=?", id);
        db.insert(
                "booking_guests",
                "booking_id,full_name,guest_type,phone",
                id,
                contact.name(),
                "ADULT",
                contact.phone());
        for (var snapshot : guests) {
            var g = snapshot.guest();
            db.insert(
                    "booking_guests",
                    "booking_id,full_name,date_of_birth,guest_type,phone,document_type,document_number_encrypted,document_number_hash",
                    id,
                    g.name(),
                    g.dateOfBirth(),
                    g.guestType(),
                    g.phone(),
                    snapshot.type(),
                    snapshot.encrypted(),
                    snapshot.hash());
        }
    }

    @PreAuthorize("hasAuthority('booking.create')")
    public Map<String, Object> voucher(long id, String code) {
        db.require(id > 0, "input");
        owned(id);
        inventory.lock();
        var clean = db.optional(code, 50);
        Long next =
                clean == null
                        ? null
                        : db
                                .rows(
                                        "SELECT id FROM vouchers WHERE UPPER(code)=UPPER(?) FOR"
                                                + " UPDATE",
                                        clean)
                                .stream()
                                .findFirst()
                                .orElseThrow(() -> new BusinessException("Mã ưu đãi không hợp lệ."))
                                .id("id");
        var b = lockPending(id);
        var old = currentVoucher(id);
        if (Objects.equals(old == null ? null : old.id("voucher_id"), next)) return view(b, true);
        ledger.editable(id);
        db.require(ledger.balance(id).collected().signum() == 0, "state");
        if (old != null)
            db.update(
                    """
                UPDATE voucher_redemptions SET status='RELEASED',released_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """,
                    old.id("id"));
        var lines = bookings.lines(id);
        db.require(lines.stream().allMatch(l -> "PENDING".equals(l.s("status"))), "state");
        var rooms =
                lines.stream()
                        .map(
                                l ->
                                        new RoomInput(
                                                l.id("room_type_id"),
                                                l.date("check_in_date"),
                                                l.date("check_out_date"),
                                                l.n("adults"),
                                                l.n("children")))
                        .toList();
        var policy = bookings.policy(b);
        Quote quote = bookings.price(rooms, clean, b.id("customer_id"), policy);
        allocation.lockInventory(rooms.stream().map(RoomInput::window).toList());
        for (int i = 0; i < lines.size(); i++) {
            long line = lines.get(i).id("id");
            var previous =
                    bookings.nights(line).stream()
                            .collect(Collectors.toMap(n -> n.date("stay_date"), n -> n));
            int number = i + 1;
            for (var night : quote.nights()) {
                if (night.line() != number) continue;
                var charge =
                        bookings.releaseNight(
                                id, line, previous.get(night.date()), null, "VOUCHER", "CANCELLED");
                bookings.postNight(id, line, night, "HELD", policy.taxPercent(), charge);
            }
        }
        if (next != null) bookings.reserveVoucher(id, b.id("customer_id"), next, quote);
        bookings.closePendingOnlinePayments(id);
        var expiry = b.time("hold_expires_at");
        var due = quote.depositDueAt();
        if (due != null && due.isBefore(expiry)) expiry = due;
        db.update(
                """
                UPDATE bookings SET deposit_required_amount=?, deposit_due_at=?, hold_expires_at=?,
                updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """,
                quote.depositRequired(),
                due == null ? null : ts(expiry),
                ts(expiry),
                id);
        db.audit(
                "ONLINE_BOOKING_VOUCHER",
                "BOOKING",
                id,
                Map.of(),
                Map.of("discount", quote.voucherDiscountTotal()),
                null);
        return view(owned(id), true);
    }

    @PreAuthorize("hasAuthority('booking.read.own')")
    @Transactional(readOnly = true)
    public Map<String, Object> details(long id) {
        return view(owned(id), true);
    }

    @PreAuthorize("hasAuthority('booking.read.own')")
    @Transactional(readOnly = true)
    public Map<String, Object> status(long id) {
        var b = owned(id);
        var state = state(b);
        var p = lastPayment(id);
        return fields(
                "id",
                id,
                "bookingCode",
                b.s("booking_code"),
                "bookingStatus",
                state,
                "status",
                state,
                "paymentStatus",
                p == null ? null : p.s("status"),
                "holdExpiresAt",
                b.time("hold_expires_at"));
    }

    @PreAuthorize("hasAuthority('booking.read.own')")
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> mine(int page, int size, String filter, String sort) {
        pagination(page, size);
        String state = filter == null ? "" : filter.strip().toUpperCase(Locale.ROOT);
        String expired =
                """
                ((b.status='PENDING' AND b.hold_expires_at IS NOT NULL AND b.hold_expires_at<=?)
                OR (b.status='CANCELLED' AND EXISTS (SELECT 1 FROM booking_cancellations x WHERE x.booking_id=b.id AND x.kind='EXPIRED')))
                """;
        String where = " WHERE " + OWN;
        List<Object> args = new ArrayList<>(List.of(db.actor()));
        if (!state.isEmpty()) {
            Instant now = Instant.now();
            if ("EXPIRED".equals(state)) {
                where += " AND " + expired;
                args.add(ts(now));
            } else if ("UPCOMING".equals(state)) {
                where +=
                        " AND (b.status IN ('CONFIRMED','CHECKED_IN') OR (b.status='PENDING' AND"
                                + " NOT "
                                + expired
                                + "))";
                args.add(ts(now));
            } else {
                if ("COMPLETED".equals(state)) state = "CHECKED_OUT";
                db.require(
                        Set.of(
                                        "PENDING",
                                        "CONFIRMED",
                                        "CANCELLED",
                                        "CHECKED_IN",
                                        "CHECKED_OUT",
                                        "NO_SHOW",
                                        "REJECTED")
                                .contains(state),
                        "input");
                where += " AND b.status=? AND NOT " + expired;
                args.add(state);
                args.add(ts(now));
            }
        }
        long total = db.count("SELECT COUNT(*) FROM bookings b" + where, args.toArray());
        args.add(size);
        args.add((long) page * size);
        String arrival =
                "(SELECT MIN(br.check_in_date) FROM booking_rooms br WHERE br.booking_id=b.id)";
        String price =
                "(SELECT COALESCE(SUM(ch.amount),0) FROM booking_charges ch WHERE"
                        + " ch.booking_id=b.id AND ch.status='POSTED')";
        String order =
                switch (Objects.toString(sort, "RECENT")) {
                    case "NEW" -> arrival + " DESC";
                    case "OLD" -> arrival + " ASC";
                    case "PRICE_DESC" -> price + " DESC";
                    case "PRICE_ASC" -> price + " ASC";
                    default -> "b.created_at DESC";
                };
        var values =
                db
                        .rows(
                                "SELECT b.* FROM bookings b"
                                        + where
                                        + " ORDER BY "
                                        + order
                                        + ",b.id DESC LIMIT ? OFFSET ?",
                                args.toArray())
                        .stream()
                        .map(b -> view(b, false))
                        .toList();
        var stats =
                db.one(
                        """
                SELECT COUNT(CASE WHEN b.status IN ('CONFIRMED','CHECKED_IN') THEN 1 END) AS upcoming,
                       COUNT(CASE WHEN b.status='CONFIRMED' THEN 1 END) AS confirmed,
                       COUNT(CASE WHEN b.status='CHECKED_OUT' THEN 1 END) AS completed,
                       COUNT(CASE WHEN b.status='CANCELLED' AND NOT EXISTS (
                           SELECT 1 FROM booking_cancellations c WHERE c.booking_id=b.id AND c.kind='EXPIRED')
                           THEN 1 END) AS cancelled
                FROM bookings b WHERE
                """
                                + OWN,
                        db.actor());
        return page(
                values,
                total,
                page,
                size,
                Map.of(
                        "upcoming",
                        stats.id("upcoming"),
                        "confirmed",
                        stats.id("confirmed"),
                        "completed",
                        stats.id("completed"),
                        "cancelled",
                        stats.id("cancelled")));
    }

    @PreAuthorize("hasAuthority('booking.cancel.own')")
    public Map<String, Object> cancel(long id, String reason) {
        owned(id);
        var b = bookings.lock(id);
        var status = state(b);
        if ("CANCELLED".equals(status)) return view(b, true);
        if ("EXPIRED".equals(status)) throw expired();
        var cancellation = cancellation(b, bookings.lines(id), ledger.balance(id), Instant.now());
        if (!Boolean.TRUE.equals(cancellation.get("allowed")))
            throw conflict(
                    "Đơn không còn đủ điều kiện hủy trực tuyến. Vui lòng liên hệ khách sạn.");
        bookings.terminate(id, null, "CANCEL", db.required(reason, 1000));
        return view(owned(id), true);
    }

    @PreAuthorize("hasAuthority('booking.create')")
    public Payment begin(long id, PaymentRequest input) {
        var b = owned(id);
        db.require("ONLINE".equals(b.s("source")) && "SANDBOX".equals(input.provider()), "input");
        b = bookings.lock(id);
        if ("CONFIRMED".equals(b.s("status"))) return result(b, lastPayment(id));
        requirePending(b);
        var balance = ledger.balance(id);
        var deposit = b.money("deposit_required_amount").min(balance.total().max(ZERO));
        assertPrice(input.expectedTotal(), balance.total());
        assertPrice(input.expectedDeposit(), deposit);
        ledger.editable(id);
        var amount = deposit.subtract(balance.net());
        if (amount.signum() <= 0) {
            bookings.confirmHeld(b, "Xác nhận đặt phòng trực tuyến", null);
            return result(owned(id), null);
        }
        sandbox();
        var pending =
                db.rows(
                        """
                SELECT * FROM payment_transactions WHERE booking_id=? AND provider='SANDBOX' AND status='PENDING'
                ORDER BY id DESC LIMIT 1
                """,
                        id);
        if (!pending.isEmpty() && pending.get(0).money("amount").compareTo(amount) == 0)
            return result(b, pending.get(0));
        bookings.closePendingOnlinePayments(id);
        String code = code("PAY");
        long transaction =
                db.insert(
                        "payment_transactions",
                        "transaction_code,booking_id,kind,method,provider,idempotency_key,amount,currency,created_by,expires_at",
                        code,
                        id,
                        "DEPOSIT",
                        "ONLINE_GATEWAY",
                        "SANDBOX",
                        "SANDBOX:" + code,
                        amount,
                        b.s("currency"),
                        db.actor(),
                        ts(b.time("hold_expires_at")));
        return result(b, payment(transaction));
    }

    @PreAuthorize("hasAuthority('booking.create')")
    public Payment process(PaymentAction input) {
        sandbox();
        db.require(
                input.transactionId() != null
                        && Set.of("SUCCESS", "FAIL").contains(Objects.toString(input.action(), "")),
                "input");
        var b = owned(payment(input.transactionId()).id("booking_id"));
        b = bookings.lock(b.id("id"));
        var p = payment(input.transactionId());
        var desired = input.action().equals("SUCCESS") ? "SUCCEEDED" : "FAILED";
        if (desired.equals(p.s("status"))) return result(b, p);
        requirePending(b);
        if (!"PENDING".equals(p.s("status")))
            throw conflict("Giao dịch này đã được xử lý. Vui lòng tạo giao dịch mới.");
        long id = b.id("id");
        if ("SUCCEEDED".equals(desired)) {
            if (!Instant.now().isBefore(p.time("expires_at"))) throw expired();
            ledger.editable(id);
            var balance = ledger.balance(id);
            assertPrice(
                    p.money("amount"),
                    b.money("deposit_required_amount")
                            .min(balance.total().max(ZERO))
                            .subtract(balance.net()));
            db.update(
                    """
                    UPDATE payment_transactions SET status='SUCCEEDED',paid_at=CURRENT_TIMESTAMP(6),
                    updated_at=CURRENT_TIMESTAMP(6),provider_transaction_id=transaction_code WHERE id=?
                    """,
                    p.id("id"));
            db.event("PAYMENT:" + p.id("id"), "PAYMENT_COLLECTED", id);
            bookings.confirmHeld(b, "Đã đặt cọc qua cổng thử nghiệm", null);
        } else {
            db.update(
                    """
                    UPDATE payment_transactions SET status='FAILED',failure_reason='SANDBOX_DECLINED',
                    updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                    """,
                    p.id("id"));
        }
        db.audit(
                "SANDBOX_" + desired,
                "PAYMENT",
                p.id("id"),
                Map.of("status", "PENDING"),
                Map.of("status", desired, "amount", p.money("amount")),
                null);
        return result(owned(id), payment(p.id("id")));
    }

    private Row payment(long id) {
        return found(
                db.rows(
                        "SELECT * FROM payment_transactions WHERE id=? AND provider='SANDBOX'",
                        id));
    }

    private Row lastPayment(long id) {
        return db
                .rows(
                        "SELECT * FROM payment_transactions WHERE booking_id=? AND"
                                + " provider='SANDBOX' ORDER BY id DESC LIMIT 1",
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Payment result(Row b, Row p) {
        return new Payment(
                p == null ? null : p.id("id"),
                p == null ? "NOT_REQUIRED" : p.s("status"),
                p == null ? ZERO : p.money("amount"),
                b.s("currency"),
                b.time("hold_expires_at"),
                state(b));
    }

    private void sandbox() {
        if (!sandboxEnabled)
            throw new BusinessException(
                    ErrorCode.FORBIDDEN, "Cổng thanh toán thử nghiệm chưa được bật.");
    }

    private void assertPrice(BigDecimal supplied, BigDecimal expected) {
        if (supplied == null || supplied.compareTo(expected) != 0)
            throw conflict("Số tiền đã thay đổi. Vui lòng tải lại và kiểm tra đơn.");
    }

    private Map<String, Object> view(Row b, boolean detail) {
        long id = b.id("id");
        var lines = bookings.lines(id);
        var first = lines.get(0);
        var balance = ledger.balance(id);
        var hotel = db.settings();
        var image =
                db
                        .rows(
                                "SELECT image_url FROM room_type_images WHERE room_type_id=? ORDER"
                                        + " BY sort_order LIMIT 1",
                                first.id("room_type_id"))
                        .stream()
                        .findFirst()
                        .map(r -> r.s("image_url"))
                        .orElse(null);
        String state = state(b);
        var result =
                fields(
                        "id",
                        id,
                        "live",
                        true,
                        "source",
                        b.s("source"),
                        "bookingCode",
                        b.s("booking_code"),
                        "bookingStatus",
                        state,
                        "currency",
                        b.s("currency"),
                        "roomsCount",
                        lines.size(),
                        "adults",
                        lines.stream().mapToInt(l -> l.n("adults")).sum(),
                        "children",
                        lines.stream().mapToInt(l -> l.n("children")).sum(),
                        "checkInDate",
                        lines.stream()
                                .map(l -> l.date("check_in_date"))
                                .min(Comparator.naturalOrder())
                                .orElseThrow(),
                        "checkOutDate",
                        lines.stream()
                                .map(l -> l.date("check_out_date"))
                                .max(Comparator.naturalOrder())
                                .orElseThrow(),
                        "amount",
                        balance.total(),
                        "depositRequired",
                        b.money("deposit_required_amount").min(balance.total().max(ZERO)),
                        "paidAmount",
                        balance.net(),
                        "amountDue",
                        balance.debt().max(ZERO),
                        "refundAmount",
                        balance.refundDue(),
                        "holdExpiresAt",
                        b.time("hold_expires_at"),
                        "createdAt",
                        b.time("created_at"),
                        "updatedAt",
                        b.time("updated_at"),
                        "guests",
                        db
                                .rows(
                                        "SELECT * FROM booking_guests WHERE booking_id=? ORDER BY"
                                                + " id",
                                        id)
                                .stream()
                                .map(
                                        g ->
                                                fields(
                                                        "id",
                                                        g.id("id"),
                                                        "name",
                                                        g.s("full_name"),
                                                        "guestType",
                                                        g.s("guest_type"),
                                                        "dateOfBirth",
                                                        date(g, "date_of_birth"),
                                                        "phone",
                                                        g.s("phone")))
                                .toList(),
                        "hotel",
                        fields(
                                "id",
                                first.id("room_type_id"),
                                "name",
                                hotel.s("name"),
                                "city",
                                hotel.s("address"),
                                "photos",
                                image == null ? List.of() : List.of(image),
                                "contactInfo",
                                fields(
                                        "address",
                                        hotel.s("address"),
                                        "phoneNumber",
                                        hotel.s("hotline"),
                                        "email",
                                        hotel.s("email"))),
                        "room",
                        fields(
                                "id",
                                first.id("room_type_id"),
                                "type",
                                first.s("room_type_name_snapshot")));
        if (!detail) return result;
        var policy = bookings.policy(b);
        var p = lastPayment(id);
        var v = currentVoucher(id);
        var stays =
                lines.stream()
                        .map(
                                l ->
                                        fields(
                                                "typeId",
                                                l.id("room_type_id"),
                                                "name",
                                                l.s("room_type_name_snapshot"),
                                                "from",
                                                l.date("check_in_date"),
                                                "to",
                                                l.date("check_out_date"),
                                                "adults",
                                                l.n("adults"),
                                                "children",
                                                l.n("children"),
                                                "status",
                                                l.s("status")))
                        .toList();
        var nights =
                db.rows(
                        """
                SELECT n.*,r.line_number,r.room_type_name_snapshot FROM booking_room_nights n
                JOIN booking_rooms r ON r.id=n.booking_room_id
                WHERE r.booking_id=? AND n.inventory_state<>'RELEASED' ORDER BY r.line_number,n.stay_date
                """,
                        id);
        var pricing =
                nights.stream()
                        .map(
                                n -> {
                                    var net = n.money("net_room_amount");
                                    var tax = percent(net, policy.taxPercent());
                                    return fields(
                                            "line",
                                            n.n("line_number"),
                                            "roomTypeName",
                                            n.s("room_type_name_snapshot"),
                                            "date",
                                            n.date("stay_date"),
                                            "nightlyRate",
                                            n.money("nightly_rate"),
                                            "lengthDiscountAmount",
                                            n.money("length_discount_amount"),
                                            "voucherDiscountAmount",
                                            n.money("voucher_discount_amount"),
                                            "netRoomAmount",
                                            net,
                                            "taxAmount",
                                            tax,
                                            "totalAmount",
                                            net.add(tax));
                                })
                        .toList();
        result.putAll(
                fields(
                        "contactName",
                        b.s("contact_name"),
                        "contactEmail",
                        b.s("contact_email"),
                        "contactPhone",
                        b.s("contact_phone"),
                        "specialRequests",
                        b.s("special_requests"),
                        "rooms",
                        stays,
                        "nights",
                        pricing,
                        "voucherCode",
                        v == null ? null : v.s("code"),
                        "voucherDiscount",
                        v == null ? ZERO : v.money("discount_amount"),
                        "taxAmount",
                        nights.stream()
                                .map(n -> percent(n.money("net_room_amount"), policy.taxPercent()))
                                .reduce(ZERO, BigDecimal::add),
                        "lengthDiscount",
                        nights.stream()
                                .map(n -> n.money("length_discount_amount"))
                                .reduce(ZERO, BigDecimal::add),
                        "policy",
                        policy.cancellation(),
                        "timezone",
                        policy.timezone(),
                        "cancellation",
                        cancellation(b, lines, balance, Instant.now()),
                        "paymentStatus",
                        p == null ? null : p.s("status"),
                        "sandboxEnabled",
                        sandboxEnabled,
                        "documentsEnabled",
                        documents.configured(),
                        "qrImage",
                        qrImage(id, state)));
        return result;
    }

    private String qrImage(long id, String state) {
        if (!Set.of("CONFIRMED", "CHECKED_IN").contains(state)) return null;
        for (var message :
                db.rows(
                        """
                SELECT qr_payload FROM guest_messages WHERE booking_id=? AND qr_payload IS NOT NULL ORDER BY id DESC
                """,
                        id)) {
            String raw = message.s("qr_payload");
            if (raw.startsWith(QR_PREFIX)
                    && db.count(
                                    """
                    SELECT COUNT(*) FROM booking_qr_tokens WHERE booking_id=? AND token_hash=?
                    AND revoked_at IS NULL AND expires_at>?
                    """,
                                    id,
                                    QrCodeSupport.hash(raw.substring(QR_PREFIX.length())),
                                    ts(Instant.now()))
                            > 0) return qr.image(raw);
        }
        return null;
    }

    private Map<String, Object> cancellation(
            Row b, List<Row> lines, BookingLedger.Balance balance, Instant now) {
        var policy = bookings.policy(b).cancellation();
        Instant deadline =
                lines.stream()
                        .map(l -> l.time("expected_check_in_at"))
                        .min(Comparator.naturalOrder())
                        .orElseThrow()
                        .minus(Duration.ofHours(policy.customerCancelDeadlineHours()));
        var state = state(b);
        boolean allowed =
                "PENDING".equals(state)
                        || ("CONFIRMED".equals(state)
                                && policy.customerCanCancel()
                                && !now.isAfter(deadline)
                                && lines.stream()
                                        .noneMatch(
                                                l ->
                                                        "CHECKED_IN".equals(l.s("status"))
                                                                || "CHECKED_OUT"
                                                                        .equals(l.s("status"))
                                                                || !now.isBefore(
                                                                        l.time(
                                                                                "expected_check_in_at"))));
        BigDecimal fee = ZERO;
        if ("CONFIRMED".equals(state)) {
            for (var l : lines) {
                if (!"CONFIRMED".equals(l.s("status"))) continue;
                var nights = bookings.nights(l.id("id"));
                var amount =
                        nights.stream()
                                .map(n -> n.money("net_room_amount"))
                                .reduce(ZERO, BigDecimal::add);
                fee =
                        fee.add(
                                BookingService.cancellationFee(
                                        policy,
                                        l,
                                        now,
                                        amount,
                                        nights.isEmpty()
                                                ? ZERO
                                                : nights.get(0).money("net_room_amount"),
                                        false));
            }
        }
        if (!allowed && Set.of("CANCELLED", "EXPIRED", "NO_SHOW").contains(state))
            fee =
                    db.sum(
                            "SELECT COALESCE(SUM(cancellation_fee),0) FROM booking_cancellations"
                                    + " WHERE booking_id=?",
                            b.id("id"));
        return fields(
                "allowed",
                allowed,
                "deadline",
                deadline,
                "fee",
                fee,
                "estimatedRefund",
                allowed ? balance.net().subtract(fee).max(ZERO) : balance.refundDue());
    }

    private Row currentVoucher(long id) {
        return db
                .rows(
                        """
                SELECT r.*,v.code FROM voucher_redemptions r JOIN vouchers v ON v.id=r.voucher_id
                WHERE r.booking_id=? AND r.status<>'RELEASED'
                """,
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Row lockPending(long id) {
        owned(id);
        var b = bookings.lock(id);
        requirePending(b);
        db.require("ONLINE".equals(b.s("source")), "state");
        return b;
    }

    private void requirePending(Row b) {
        var s = state(b);
        if ("EXPIRED".equals(s)) throw expired();
        if (!"PENDING".equals(s)) throw conflict("Đơn đặt phòng không còn ở trạng thái giữ chỗ.");
    }

    private String state(Row b) {
        String s = b.s("status");
        if ("PENDING".equals(s)
                && b.time("hold_expires_at") != null
                && !Instant.now().isBefore(b.time("hold_expires_at"))) return "EXPIRED";
        if ("CANCELLED".equals(s)
                && db.count(
                                """
                SELECT COUNT(*) FROM booking_cancellations WHERE booking_id=? AND kind='EXPIRED'
                """,
                                b.id("id"))
                        > 0) return "EXPIRED";
        return s;
    }

    private Row owned(long id) {
        return found(db.rows("SELECT b.* FROM bookings b WHERE b.id=? AND " + OWN, id, db.actor()));
    }

    private Row customer() {
        return found(db.rows("SELECT * FROM customers WHERE user_id=?", db.actor()));
    }

    private Row found(List<Row> result) {
        return result.stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.RESOURCE_NOT_FOUND,
                                        "Không tìm thấy dữ liệu yêu cầu."));
    }

    private static BusinessException expired() {
        return new BusinessException(
                ErrorCode.BOOKING_HOLD_EXPIRED, "Đã hết thời gian giữ phòng. Vui lòng đặt lại.");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BOOKING_CONFLICT, message);
    }

    private record Contact(String name, String email, String phone) {}

    private Contact contact(Row fallback, String name, String mail, String phone) {
        String prefix = fallback.data().containsKey("contact_name") ? "contact_" : "";
        name =
                db.required(
                        name == null
                                ? fallback.s(prefix.isEmpty() ? "full_name" : "contact_name")
                                : name,
                        150);
        mail =
                db.required(mail == null ? fallback.s(prefix + "email") : mail, 150)
                        .toLowerCase(Locale.ROOT);
        phone = phone(phone == null ? fallback.s(prefix + "phone") : phone);
        if (phone == null || !mail.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new BusinessException("Vui lòng nhập email và số điện thoại hợp lệ.");
        return new Contact(name, mail, phone);
    }

    private String phone(String value) {
        String phone = db.optional(value, 20);
        if (phone != null
                && (!phone.matches("\\+?[0-9(). -]+") || phone.replaceAll("\\D", "").length() < 7))
            throw new BusinessException("Số điện thoại không hợp lệ.");
        return phone;
    }

    @PreAuthorize("hasAuthority('profile.manage.own')")
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> guests(int page, int size) {
        pagination(page, size);
        long owner = customer().id("id");
        long total = db.count("SELECT COUNT(*) FROM saved_guests WHERE customer_id=?", owner);
        var values =
                db
                        .rows(
                                "SELECT * FROM saved_guests WHERE customer_id=? ORDER BY id DESC"
                                        + " LIMIT ? OFFSET ?",
                                owner,
                                size,
                                (long) page * size)
                        .stream()
                        .map(this::guestView)
                        .toList();
        return page(values, total, page, size, null);
    }

    @PreAuthorize("hasAuthority('profile.manage.own')")
    public Map<String, Object> saveGuest(Long id, GuestInput input) {
        long owner = customer().id("id");
        var g = cleanGuest(input);
        var doc = document(g);
        if (id == null) {
            id =
                    db.insert(
                            "saved_guests",
                            "customer_id,full_name,date_of_birth,gender,guest_type,phone,document_type,document_number_encrypted,document_number_hash",
                            owner,
                            g.name(),
                            g.dateOfBirth(),
                            g.gender(),
                            g.guestType(),
                            g.phone(),
                            doc.type(),
                            doc.encrypted(),
                            doc.hash());
        } else {
            var prior = saved(owner, id);
            if (input.documentNumber() == null)
                doc =
                        new GuestSnapshot(
                                g,
                                prior.s("document_type"),
                                prior.s("document_number_encrypted"),
                                prior.s("document_number_hash"));
            db.update(
                    """
                    UPDATE saved_guests SET full_name=?,date_of_birth=?,gender=?,guest_type=?,phone=?,
                    document_type=?,document_number_encrypted=?,document_number_hash=? WHERE id=? AND customer_id=?
                    """,
                    g.name(),
                    g.dateOfBirth(),
                    g.gender(),
                    g.guestType(),
                    g.phone(),
                    doc.type(),
                    doc.encrypted(),
                    doc.hash(),
                    id,
                    owner);
        }
        return guestView(saved(owner, id));
    }

    @PreAuthorize("hasAuthority('profile.manage.own')")
    public void deleteGuest(long id) {
        long owner = customer().id("id");
        saved(owner, id);
        db.update("DELETE FROM saved_guests WHERE id=? AND customer_id=?", id, owner);
    }

    private GuestInput cleanGuest(GuestInput g) {
        var gender = db.optional(g.gender(), 20);
        var type = g.guestType() == null ? "ADULT" : g.guestType();
        db.require(
                Set.of("ADULT", "CHILD").contains(type)
                        && (gender == null || Set.of("MALE", "FEMALE", "OTHER").contains(gender))
                        && (g.dateOfBirth() == null
                                || (!g.dateOfBirth().isAfter(db.today())
                                        && !g.dateOfBirth().isBefore(db.today().minusYears(130)))),
                "guests");
        return new GuestInput(
                db.required(g.name(), 150),
                g.dateOfBirth(),
                gender,
                type,
                phone(g.phone()),
                db.optional(g.documentType(), 30),
                db.optional(g.documentNumber(), 60));
    }

    private Row saved(long owner, long id) {
        return found(db.rows("SELECT * FROM saved_guests WHERE id=? AND customer_id=?", id, owner));
    }

    private GuestSnapshot document(GuestInput g) {
        if (g.documentNumber() == null) return new GuestSnapshot(g, null, null, null);
        String type = Objects.toString(g.documentType(), "");
        db.require(Set.of("ID", "PASSPORT").contains(type), "guests");
        String number = documents.normalize(g.documentNumber());
        db.require(number.matches("[A-Z0-9]{5,32}"), "guests");
        return new GuestSnapshot(g, type, documents.encrypt(number), documents.hash(number));
    }

    private GuestInput guestInput(Row g) {
        return new GuestInput(
                g.s("full_name"),
                date(g, "date_of_birth"),
                g.s("gender"),
                g.s("guest_type"),
                g.s("phone"),
                null,
                null);
    }

    private Map<String, Object> guestView(Row g) {
        return fields(
                "id",
                g.id("id"),
                "name",
                g.s("full_name"),
                "dateOfBirth",
                date(g, "date_of_birth"),
                "gender",
                g.s("gender"),
                "guestType",
                g.s("guest_type"),
                "phone",
                g.s("phone"),
                "documentType",
                g.s("document_type"),
                "hasDocument",
                g.s("document_number_encrypted") != null);
    }

    private static LocalDate date(Row row, String key) {
        return row.s(key) == null ? null : row.date(key);
    }

    private void pagination(int page, int size) {
        db.require(page >= 0 && size >= 1 && size <= 100, "input");
    }

    private static <T> Page<T> page(
            List<T> values, long total, int page, int size, Map<String, Long> stats) {
        var pages = (int) ((total + size - 1) / size);
        return new Page<>(
                values,
                total,
                pages,
                page,
                size,
                page == 0,
                page >= pages - 1,
                values.isEmpty(),
                values.size(),
                stats);
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
