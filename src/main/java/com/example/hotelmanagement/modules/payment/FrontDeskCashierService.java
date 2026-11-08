package com.example.hotelmanagement.modules.payment;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue;
import com.example.hotelmanagement.modules.invoice.InvoiceService;
import com.example.hotelmanagement.modules.reception.QrCodeSupport;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FrontDeskCashierService {
    private final OperationsStore db;
    private final BookingLedger ledger;
    private final FrontDeskPaymentService payments;
    private final HotelServiceCatalogue catalogue;
    private final InvoiceService invoices;
    private final BankTransferQr bank;
    private final PaymentProofStorage proofs;

    public record Money(
            BigDecimal total,
            BigDecimal collected,
            BigDecimal refunded,
            BigDecimal net,
            BigDecimal debt,
            BigDecimal refundDue,
            BigDecimal depositRequired,
            BigDecimal depositShortfall,
            boolean canDeposit,
            boolean finalRooms,
            Long invoice) {}

    public record Shift(Long id, boolean employee, BigDecimal expected) {}

    public record Snapshot(
            Map<String, Object> booking,
            Money balance,
            Shift shift,
            BankTransferQr.Settings bank,
            String token,
            List<Map<String, Object>> rooms,
            List<Map<String, Object>> categories,
            List<Map<String, Object>> items,
            List<Map<String, Object>> usages,
            List<Map<String, Object>> transactions,
            List<Map<String, Object>> requests) {}

    @PreAuthorize("hasAuthority('booking.read.any')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(long booking) {
        var b =
                db.one(
                        "SELECT"
                            + " id,booking_code,contact_name,contact_phone,status,hold_expires_at,currency,deposit_required_amount,version"
                            + " FROM bookings WHERE id=?",
                        booking);
        var balance = ledger.balance(booking);
        var required = b.money("deposit_required_amount").min(balance.total().max(ZERO));
        Long invoice =
                db.one(
                                "SELECT MAX(id) AS id FROM invoices WHERE booking_id=? AND"
                                    + " status='ISSUED'",
                                booking)
                        .id("id");
        boolean finalRooms =
                db.count(
                                "SELECT COUNT(*) FROM booking_rooms WHERE booking_id=? AND status"
                                    + " IN ('PENDING','CONFIRMED','CHECKED_IN')",
                                booking)
                        == 0;
        boolean collect = has("payment.collect"), refund = has("payment.refund");
        var money =
                new Money(
                        balance.total(),
                        balance.collected(),
                        balance.refunded(),
                        balance.net(),
                        balance.debt(),
                        balance.refundDue(),
                        required,
                        required.subtract(balance.net()).max(ZERO),
                        "PENDING".equals(b.s("status"))
                                && b.time("hold_expires_at") != null
                                && Instant.now().isBefore(b.time("hold_expires_at")),
                        finalRooms,
                        invoice);
        var rooms =
                db
                        .rows(
                                """
            SELECT br.id,br.line_number,br.status,br.room_type_name_snapshot,r.room_number FROM booking_rooms br
            LEFT JOIN room_allocations a ON a.id=(SELECT MAX(x.id) FROM room_allocations x WHERE x.booking_room_id=br.id)
            LEFT JOIN rooms r ON r.id=a.room_id WHERE br.booking_id=? ORDER BY br.line_number
            """,
                                booking)
                        .stream()
                        .map(Row::data)
                        .toList();
        var categories =
                has("service.read") || has("service.usage.manage")
                        ? db
                                .rows(
                                        "SELECT id,name FROM service_categories WHERE"
                                            + " is_active=TRUE ORDER BY name")
                                .stream()
                                .map(Row::data)
                                .toList()
                        : List.<Map<String, Object>>of();
        var items =
                has("service.read") || has("service.usage.manage")
                        ? db
                                .rows(
                                        "SELECT"
                                            + " s.id,s.category_id,s.name,s.unit,s.unit_price,s.tax_percent"
                                            + " FROM services s JOIN service_categories c ON"
                                            + " c.id=s.category_id WHERE s.is_active=TRUE AND"
                                            + " c.is_active=TRUE ORDER BY c.name,s.name")
                                .stream()
                                .map(Row::data)
                                .toList()
                        : List.<Map<String, Object>>of();
        var usages =
                db
                        .rows(
                                """
            SELECT s.id,s.booking_room_id,s.service_name_snapshot,s.unit_snapshot,s.quantity,s.unit_price_snapshot,s.tax_percent_snapshot,
            s.status,s.used_at,s.void_reason,s.notes,u.full_name AS recorded_by,r.room_number
            FROM service_usages s LEFT JOIN users u ON u.id=s.recorded_by LEFT JOIN room_allocations a ON a.id=s.room_allocation_id
            LEFT JOIN rooms r ON r.id=a.room_id WHERE s.booking_id=? ORDER BY s.used_at DESC,s.id DESC
            """,
                                booking)
                        .stream()
                        .map(
                                row -> {
                                    var value = new LinkedHashMap<>(row.data());
                                    BigDecimal subtotal =
                                            vnd(
                                                    row.money("quantity")
                                                            .multiply(
                                                                    row.money(
                                                                            "unit_price_snapshot"))
                                                            .setScale(
                                                                    2,
                                                                    java.math.RoundingMode
                                                                            .HALF_UP));
                                    value.put(
                                            "total",
                                            subtotal.add(
                                                    percent(
                                                            subtotal,
                                                            row.money("tax_percent_snapshot"))));
                                    return (Map<String, Object>) value;
                                })
                        .toList();
        var transactions =
                db
                        .rows(
                                """
            SELECT p.id,p.transaction_code,p.kind,p.method,p.status,p.amount,p.paid_at,p.reason,p.provider_transaction_id,p.work_session_id,
            p.parent_payment_id,u.full_name AS recorded_by,CASE WHEN p.receipt_storage_key IS NULL THEN 0 ELSE 1 END AS has_proof,
            p.amount-COALESCE((SELECT SUM(x.amount) FROM payment_transactions x WHERE x.parent_payment_id=p.id AND x.kind='REFUND' AND x.status IN ('SUCCEEDED','PENDING')),0) AS remaining
            FROM payment_transactions p LEFT JOIN users u ON u.id=p.created_by WHERE p.booking_id=? ORDER BY p.id DESC
            """,
                                booking)
                        .stream()
                        .map(Row::data)
                        .toList();
        var requests =
                collect || refund
                        ? db
                                .rows(
                                        """
            SELECT r.id,r.parent_payment_id,r.amount,r.method,r.reason,r.status,r.created_at,r.response_note,r.payment_transaction_id,r.requested_by,u.full_name AS requester
            FROM refund_requests r JOIN users u ON u.id=r.requested_by WHERE r.booking_id=? AND (?=TRUE OR r.requested_by=?) ORDER BY r.id DESC LIMIT 100
            """,
                                        booking,
                                        refund,
                                        db.actor())
                                .stream()
                                .map(Row::data)
                                .toList()
                        : List.<Map<String, Object>>of();
        var context = new LinkedHashMap<String, Object>(b.data());
        context.put("timezone", db.zone().getId());
        return new Snapshot(
                context,
                money,
                shift(),
                collect || refund || has("hotel.manage") ? bank.settings() : null,
                token(booking),
                rooms,
                categories,
                items,
                usages,
                transactions,
                requests);
    }

    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public long collect(
            long booking,
            String key,
            String kind,
            String method,
            BigDecimal amount,
            String reason,
            String reference,
            String expected,
            MultipartFile proof) {
        db.lockBooking(booking);
        boolean retry =
                db.count("SELECT COUNT(*) FROM payment_transactions WHERE idempotency_key=?", key)
                        > 0;
        if (!retry) db.require(Objects.equals(token(booking), expected), "priceChanged");
        db.require(proof == null || proof.isEmpty() || "BANK_TRANSFER".equals(method), "input");
        long id = payments.collectReferenced(booking, key, kind, method, amount, reason, reference);
        proofs.attach(id, proof);
        return id;
    }

    @PreAuthorize("hasAuthority('service.usage.manage')")
    public long service(
            long booking,
            long line,
            long service,
            BigDecimal quantity,
            String key,
            String notes,
            BigDecimal price,
            BigDecimal tax) {
        db.require(price != null && tax != null, "priceChanged");
        return catalogue.useReviewed(booking, line, service, quantity, key, notes, price, tax);
    }

    @PreAuthorize("hasAuthority('invoice.manage')")
    public long invoice(
            long booking, String company, String address, String taxCode, String expected) {
        db.lockBooking(booking);
        if (db.count(
                        "SELECT COUNT(*) FROM invoices WHERE booking_id=? AND status='ISSUED'",
                        booking)
                == 0) db.require(Objects.equals(token(booking), expected), "priceChanged");
        return invoices.issue(booking, address, taxCode, company);
    }

    @PreAuthorize(
            "hasAuthority('booking.read.any') and"
                + " hasAnyAuthority('payment.collect','payment.refund')")
    @Transactional(readOnly = true)
    public PaymentProofStorage.Download proof(long payment) {
        var p = db.one("SELECT receipt_storage_key FROM payment_transactions WHERE id=?", payment);
        return proofs.read(p.s("receipt_storage_key"));
    }

    @PreAuthorize("hasAuthority('payment.refund')")
    public long refund(
            long booking,
            String key,
            long parent,
            String method,
            BigDecimal amount,
            String reason,
            String reference,
            String expected) {
        db.lockBooking(booking);
        if (db.count("SELECT COUNT(*) FROM payment_transactions WHERE idempotency_key=?", key) == 0)
            db.require(Objects.equals(token(booking), expected), "priceChanged");
        return payments.refundReferenced(booking, key, parent, method, amount, reason, reference);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','report.shift')")
    @Transactional(readOnly = true)
    public Shift currentShift() {
        return shift();
    }

    private String token(long booking) {
        var b =
                db.one(
                        "SELECT version,status,deposit_required_amount,hold_expires_at FROM"
                            + " bookings WHERE id=?",
                        booking);
        var balance = ledger.balance(booking);
        return QrCodeSupport.hash(
                db.json(
                        List.of(
                                booking,
                                b.n("version"),
                                b.s("status"),
                                Objects.toString(b.time("hold_expires_at"), ""),
                                b.money("deposit_required_amount").toPlainString(),
                                balance.total().toPlainString(),
                                balance.collected().toPlainString(),
                                balance.refunded().toPlainString(),
                                db.count(
                                        "SELECT COALESCE(MAX(id),0) FROM booking_charges WHERE"
                                            + " booking_id=?",
                                        booking),
                                db.count(
                                        "SELECT COUNT(*) FROM booking_charges WHERE booking_id=?"
                                            + " AND status='POSTED'",
                                        booking),
                                db.count(
                                        "SELECT COUNT(*) FROM invoices WHERE booking_id=? AND"
                                            + " status='ISSUED'",
                                        booking))));
    }

    private Shift shift() {
        var employees =
                db.rows(
                        "SELECT id FROM employees WHERE user_id=? AND ended_on IS NULL",
                        db.actor());
        if (employees.size() != 1) return new Shift(null, false, ZERO);
        var open =
                db.rows(
                        "SELECT id,opening_cash FROM work_sessions WHERE employee_id=? AND"
                            + " closed_at IS NULL",
                        employees.get(0).id("id"));
        if (open.size() != 1) return new Shift(null, true, ZERO);
        var row = open.get(0);
        return new Shift(
                row.id("id"),
                true,
                row.money("opening_cash")
                        .add(
                                db.sum(
                                        "SELECT COALESCE(SUM(CASE WHEN kind='REFUND' THEN -amount"
                                            + " ELSE amount END),0) FROM payment_transactions WHERE"
                                            + " work_session_id=? AND status='SUCCEEDED' AND"
                                            + " method='CASH'",
                                        row.id("id"))));
    }

    private boolean has(String code) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(code));
    }
}
