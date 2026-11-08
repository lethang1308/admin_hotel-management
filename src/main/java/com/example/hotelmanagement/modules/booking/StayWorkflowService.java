package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.invoice.InvoiceService;
import com.example.hotelmanagement.modules.payment.*;

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
public class StayWorkflowService {
    private final OperationsStore db;
    private final StayService stays;
    private final BookingService bookings;
    private final BookingDeskOperations desk;
    private final BookingLedger ledger;
    private final FrontDeskPaymentService payments;
    private final InvoiceService invoices;
    private final com.example.hotelmanagement.modules.customer.service.IdentityDocumentCrypto
            documents;
    private final HotelInventoryCoordinator inventory;
    private final RoomAllocationService allocation;

    public record Line(
            long id,
            String room,
            String type,
            int adults,
            int children,
            boolean ready,
            StayService.StayPreview preview) {}

    public record Contact(String code, String name, String phone) {}

    public record Registered(String name, LocalDate birth, String nationality, String document) {}

    public record Bill(
            BigDecimal total,
            BigDecimal netPaid,
            BigDecimal projectedTotal,
            BigDecimal projectedDebt,
            BigDecimal refundDue,
            List<Line> lines,
            long version,
            boolean finalRooms,
            List<Map<String, Object>> charges,
            List<Map<String, Object>> services,
            List<Map<String, Object>> refundable,
            List<RoomAllocationService.Candidate> alternatives,
            List<Registered> guests,
            Contact contact,
            String key) {}

    public record Receipt(long booking, Long invoice) {}

    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.checkin')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Bill checkinPreview(long booking, long line) {
        return preview(booking, List.of(line), true);
    }

    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.checkout')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Bill checkoutPreview(long booking, List<Long> lines) {
        return preview(booking, lines, false);
    }

    private Bill preview(long booking, List<Long> ids, boolean checkin) {
        db.require(
                ids != null
                        && !ids.isEmpty()
                        && ids.size() <= 12
                        && new HashSet<>(ids).size() == ids.size(),
                "input");
        var order = db.one("SELECT * FROM bookings WHERE id=?", booking);
        var balance = ledger.balance(booking);
        var lines = new ArrayList<Line>();
        BigDecimal difference = ZERO;
        List<RoomAllocationService.Candidate> alternatives = List.of();
        for (long id : ids) {
            var br = db.bookingRoom(booking, id);
            var p =
                    checkin
                            ? stays.previewCheckIn(booking, id)
                            : stays.previewCheckOut(booking, id);
            var a =
                    db.one(
                            "SELECT * FROM room_allocations WHERE booking_room_id=? AND status IN"
                                    + " ('SCHEDULED','OCCUPIED')",
                            id);
            var room = db.one("SELECT * FROM rooms WHERE id=?", a.id("room_id"));
            boolean ready = true;
            if (checkin) {
                var candidates =
                        allocation.candidates(
                                new RoomAllocationService.Window(
                                        br.id("room_type_id"),
                                        br.date("check_in_date"),
                                        br.date("check_out_date")),
                                a.id("id"),
                                true);
                ready =
                        br.date("check_in_date").equals(db.today())
                                && candidates.stream()
                                        .anyMatch(c -> c.id() == room.id("id") && c.available());
                alternatives =
                        candidates.stream()
                                .filter(c -> c.id() != room.id("id") && c.available())
                                .toList();
            }
            lines.add(
                    new Line(
                            id,
                            room.s("room_number"),
                            br.s("room_type_name_snapshot"),
                            br.n("adults"),
                            br.n("children"),
                            ready,
                            p));
            difference = difference.add(p.projectedDebt().subtract(p.currentDebt()));
        }
        boolean finalRooms =
                db
                        .rows(
                                "SELECT id FROM booking_rooms WHERE booking_id=? AND status IN"
                                        + " ('PENDING','CONFIRMED','CHECKED_IN')",
                                booking)
                        .stream()
                        .allMatch(r -> ids.contains(r.id("id")));
        var debt = balance.debt().add(difference);
        var charges =
                db
                        .rows(
                                "SELECT id,booking_room_id,description,kind,amount,service_date"
                                    + " FROM booking_charges WHERE booking_id=? AND status='POSTED'"
                                    + " ORDER BY service_date,id",
                                booking)
                        .stream()
                        .map(Row::data)
                        .toList();
        var services =
                db
                        .rows(
                                "SELECT s.id,s.name,s.unit,s.unit_price FROM services s JOIN"
                                        + " service_categories c ON c.id=s.category_id WHERE"
                                        + " s.is_active=TRUE AND c.is_active=TRUE ORDER BY s.name")
                        .stream()
                        .map(Row::data)
                        .toList();
        var refundable =
                db
                        .rows(
                                """
            SELECT p.id,p.transaction_code,p.method,p.amount-COALESCE((SELECT SUM(r.amount) FROM payment_transactions r WHERE r.parent_payment_id=p.id AND r.kind='REFUND' AND r.status IN ('PENDING','SUCCEEDED')),0) AS remaining
            FROM payment_transactions p WHERE p.booking_id=? AND p.kind<>'REFUND' AND p.status='SUCCEEDED' ORDER BY p.id
            """,
                                booking)
                        .stream()
                        .filter(r -> r.money("remaining").signum() > 0)
                        .map(Row::data)
                        .toList();
        return new Bill(
                balance.total(),
                balance.net(),
                balance.total().add(difference),
                debt,
                debt.negate().max(ZERO),
                lines,
                order.n("version"),
                finalRooms,
                charges,
                services,
                refundable,
                alternatives,
                db
                        .rows(
                                "SELECT DISTINCT g.*,sg.booking_room_id AS line_id FROM"
                                        + " booking_guests g JOIN stay_guests sg ON"
                                        + " sg.booking_guest_id=g.id AND sg.booking_id=g.booking_id"
                                        + " WHERE sg.booking_id=?",
                                booking)
                        .stream()
                        .filter(r -> ids.contains(r.id("line_id")))
                        .map(
                                r ->
                                        new Registered(
                                                r.s("full_name"),
                                                r.s("date_of_birth") == null
                                                        ? null
                                                        : r.date("date_of_birth"),
                                                r.s("nationality_code"),
                                                r.s("document_number_encrypted") == null
                                                                || !documents.configured()
                                                        ? null
                                                        : documents.masked(
                                                                r.s("document_number_encrypted"))))
                        .toList(),
                new Contact(
                        order.s("booking_code"), order.s("contact_name"), order.s("contact_phone")),
                code(""));
    }

    @PreAuthorize("hasAuthority('booking.checkin')")
    public Receipt checkin(
            long booking,
            long line,
            List<StayService.Guest> guests,
            String reason,
            String key,
            String method,
            BigDecimal amount,
            Long cleanRoom,
            BigDecimal expectedDebt,
            long version) {
        var order = bookings.lock(booking);
        if ("CHECKED_IN".equals(db.bookingRoom(booking, line).s("status")))
            return new Receipt(booking, null);
        var bill = preview(booking, List.of(line), true);
        reviewed(order, bill, expectedDebt, version);
        if (cleanRoom != null) desk.assign(booking, line, cleanRoom, reason);
        db.require(
                amount != null
                        && amount.signum() >= 0
                        && amount.compareTo(bill.projectedDebt().max(ZERO)) <= 0,
                "amount");
        stays.checkInAndCollect(
                booking,
                line,
                guests,
                reason,
                new StayService.Settlement(key, method, amount, bill.projectedDebt()));
        db.require(
                ledger.balance(booking).debt().compareTo(bill.projectedDebt().subtract(amount))
                        == 0,
                "priceChanged");
        return new Receipt(booking, null);
    }

    @PreAuthorize("hasAuthority('booking.checkout')")
    public Receipt checkout(
            long booking,
            List<Long> lines,
            String reason,
            String key,
            String method,
            BigDecimal amount,
            String paymentAction,
            Long parent,
            BigDecimal expectedDebt,
            long version,
            boolean issueInvoice,
            String buyerAddress,
            String taxCode) {
        return checkoutWithCompany(
                booking,
                lines,
                reason,
                key,
                method,
                amount,
                paymentAction,
                parent,
                expectedDebt,
                version,
                issueInvoice,
                buyerAddress,
                taxCode,
                null);
    }

    @PreAuthorize("hasAuthority('booking.checkout')")
    public Receipt checkoutWithCompany(
            long booking,
            List<Long> lines,
            String reason,
            String key,
            String method,
            BigDecimal amount,
            String paymentAction,
            Long parent,
            BigDecimal expectedDebt,
            long version,
            boolean issueInvoice,
            String buyerAddress,
            String taxCode,
            String company) {
        var order = bookings.lock(booking);
        db.require(
                lines != null
                        && !lines.isEmpty()
                        && lines.size() <= 12
                        && new HashSet<>(lines).size() == lines.size(),
                "input");
        var selected = lines.stream().map(id -> db.bookingRoom(booking, id)).toList();
        if (selected.stream().allMatch(r -> "CHECKED_OUT".equals(r.s("status"))))
            return receipt(booking, issueInvoice, buyerAddress, taxCode, company);
        var bill = preview(booking, lines, false);
        reviewed(order, bill, expectedDebt, version);
        reason = db.required(reason, 500);
        db.require(
                paymentAction != null
                        && Set.of("NONE", "COLLECT", "REFUND").contains(paymentAction),
                "input");
        db.require(amount != null && amount.signum() >= 0, "amount");
        if (paymentAction.equals("NONE")) db.require(amount.signum() == 0, "amount");
        if (paymentAction.equals("COLLECT")) {
            db.require(
                    amount.signum() > 0 && amount.compareTo(bill.projectedDebt().max(ZERO)) <= 0,
                    "amount");
            payments.collect(booking, key, "PAYMENT", method, amount, reason);
        }
        // Final-room debt is checked by the shared core. Any failure rolls back the whole group.
        for (long line : lines) stays.checkOut(booking, line, reason);
        BigDecimal expectedAfterCollection =
                bill.projectedDebt().subtract(paymentAction.equals("COLLECT") ? amount : ZERO);
        db.require(
                ledger.balance(booking).debt().compareTo(expectedAfterCollection) == 0,
                "priceChanged");
        if (paymentAction.equals("REFUND")) {
            db.require(parent != null && amount.signum() > 0, "refund");
            payments.refund(booking, key, parent, method, amount, reason);
        }
        return receipt(booking, issueInvoice, buyerAddress, taxCode, company);
    }

    private Receipt receipt(
            long booking, boolean issue, String address, String taxCode, String company) {
        return new Receipt(
                booking, issue ? invoices.issue(booking, address, taxCode, company) : null);
    }

    private void reviewed(Row order, Bill bill, BigDecimal expected, long version) {
        db.require(
                version == order.n("version")
                        && expected != null
                        && expected.compareTo(bill.projectedDebt()) == 0,
                "priceChanged");
    }

    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('booking.checkin')")
    public long priority(long booking, long line, String reason) {
        bookings.lock(booking);
        var br = db.bookingRoom(booking, line);
        db.require("CONFIRMED".equals(br.s("status")), "state");
        var a =
                db.one(
                        "SELECT room_id FROM room_allocations WHERE booking_room_id=? AND"
                                + " status='SCHEDULED'",
                        line);
        long room = a.id("room_id");
        allocation.lockRooms(List.of(room));
        db.require(!inventory.openStay(room) && !inventory.currentBlock(room), "notReady");
        reason = db.required(reason, 500);
        var active =
                db.rows(
                        "SELECT id,notes FROM housekeeping_tasks WHERE room_id=? AND status IN"
                                + " ('PENDING','IN_PROGRESS','COMPLETED') ORDER BY id DESC",
                        room);
        long task;
        String note = "Ưu tiên đón khách / Priority arrival · " + reason;
        if (active.isEmpty())
            task =
                    db.insert(
                            "housekeeping_tasks",
                            "room_id,created_by,notes",
                            room,
                            db.actor(),
                            note);
        else {
            task = active.get(0).id("id");
            db.update("UPDATE housekeeping_tasks SET notes=? WHERE id=?", note, task);
        }
        db.audit(
                "HOUSEKEEPING_PRIORITY",
                "ROOM",
                room,
                Map.of(),
                Map.of("task", task, "booking", booking),
                reason);
        return task;
    }

    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('room.status.update')")
    public void clean(long task, String action, String reason) {
        inventory.lock();
        var initial = db.one("SELECT room_id FROM housekeeping_tasks WHERE id=?", task);
        long room = initial.id("room_id");
        allocation.lockRooms(List.of(room));
        var t = db.one("SELECT * FROM housekeeping_tasks WHERE id=? FOR UPDATE", task);
        var r = db.one("SELECT * FROM rooms WHERE id=?", room);
        db.require(
                !inventory.openStay(room)
                        && !inventory.currentBlock(room)
                        && Set.of("DIRTY", "CLEANING", "AVAILABLE").contains(r.s("status")),
                "notReady");
        reason = db.required(reason, 500);
        String next;
        switch (action) {
            case "start" -> {
                db.require("PENDING".equals(t.s("status")), "state");
                next = "IN_PROGRESS";
                db.update(
                        "UPDATE housekeeping_tasks SET started_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                        task);
            }
            case "complete" -> {
                db.require("IN_PROGRESS".equals(t.s("status")), "state");
                next = "COMPLETED";
                db.update(
                        "UPDATE housekeeping_tasks SET completed_at=CURRENT_TIMESTAMP(6) WHERE"
                                + " id=?",
                        task);
            }
            case "verify" -> {
                db.require("COMPLETED".equals(t.s("status")), "state");
                next = "VERIFIED";
                db.update(
                        "UPDATE housekeeping_tasks SET"
                                + " verified_by=?,verified_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                        db.actor(),
                        task);
            }
            default -> {
                db.require(false, "input");
                throw new IllegalArgumentException();
            }
        }
        db.update("UPDATE housekeeping_tasks SET status=? WHERE id=?", next, task);
        String roomStatus =
                next.equals("VERIFIED") && !inventory.unverifiedCleaning(room)
                        ? "AVAILABLE"
                        : "CLEANING";
        db.update(
                "UPDATE rooms SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE"
                        + " id=?",
                roomStatus,
                room);
        if (!r.s("status").equals(roomStatus))
            db.insert(
                    "room_status_history",
                    "room_id,old_status,new_status,changed_by,reason",
                    room,
                    r.s("status"),
                    roomStatus,
                    db.actor(),
                    reason);
        db.audit(
                "HOUSEKEEPING_" + next,
                "ROOM",
                room,
                Map.of("taskStatus", t.s("status")),
                Map.of("task", task),
                reason);
    }
}
