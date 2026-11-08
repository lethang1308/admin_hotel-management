package com.example.hotelmanagement.modules.payment;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FrontDeskPaymentService {
    private final OperationsStore db;
    private final BookingLedger ledger;

    @PreAuthorize("hasAuthority('payment.collect')")
    public long collect(
            long booking,
            String key,
            String kind,
            String method,
            BigDecimal amount,
            String reason) {
        return collectReferenced(booking, key, kind, method, amount, reason, null);
    }

    @PreAuthorize("hasAuthority('payment.collect')")
    public long collectReferenced(
            long booking,
            String key,
            String kind,
            String method,
            BigDecimal amount,
            String reason,
            String reference) {
        var row = db.lockBooking(booking);
        db.require("DEPOSIT".equals(kind) || "PAYMENT".equals(kind), "input");
        return record(row, key, kind, method, amount, null, reason, reference);
    }

    @PreAuthorize("hasAuthority('payment.refund')")
    public long refund(
            long booking,
            String key,
            long parent,
            String method,
            BigDecimal amount,
            String reason) {
        return refundReferenced(booking, key, parent, method, amount, reason, null);
    }

    @PreAuthorize("hasAuthority('payment.refund')")
    public long refundReferenced(
            long booking,
            String key,
            long parent,
            String method,
            BigDecimal amount,
            String reason,
            String reference) {
        reason = refundReason(reason);
        return record(
                db.lockBooking(booking), key, "REFUND", method, amount, parent, reason, reference);
    }

    private long record(
            Row booking,
            String key,
            String kind,
            String method,
            BigDecimal amount,
            Long parent,
            String reason,
            String reference) {
        long bookingId = booking.id("id");
        db.require(key != null && key.matches("[A-F0-9]{24}") && db.actor() != null, "input");
        db.require("CASH".equals(method) || "BANK_TRANSFER".equals(method), "input");
        amount = money(amount, false);
        reference = db.optional(reference, 150);
        db.require(reference == null || "BANK_TRANSFER".equals(method), "input");
        var existing = db.rows("SELECT * FROM payment_transactions WHERE idempotency_key=?", key);
        if (!existing.isEmpty()) {
            var payment = existing.get(0);
            db.require(
                    payment.id("booking_id") == bookingId
                            && Objects.equals(payment.id("created_by"), db.actor())
                            && payment.s("kind").equals(kind)
                            && payment.s("method").equals(method)
                            && Objects.equals(payment.id("parent_payment_id"), parent)
                            && payment.money("amount").compareTo(amount) == 0
                            && Objects.equals(payment.s("provider_transaction_id"), reference),
                    "idempotency");
            return payment.id("id");
        }
        boolean refund = parent != null;
        var balance = ledger.balance(bookingId);
        if (refund) {
            var original =
                    db.one(
                            "SELECT * FROM payment_transactions WHERE id=? AND booking_id=? FOR"
                                    + " UPDATE",
                            parent,
                            bookingId);
            db.require(
                    original.s("status").equals("SUCCEEDED")
                            && !original.s("kind").equals("REFUND")
                            && original.s("currency").equals(booking.s("currency")),
                    "refund");
            var committed =
                    db.sum(
                            """
                    SELECT COALESCE(SUM(amount),0) FROM payment_transactions
                    WHERE parent_payment_id=? AND kind='REFUND' AND status IN ('PENDING','SUCCEEDED')
                    """,
                            parent);
            db.require(
                    amount.compareTo(original.money("amount").subtract(committed)) <= 0
                            && amount.compareTo(balance.refundDue()) <= 0,
                    "refund");
        } else {
            var status = booking.s("status");
            if (kind.equals("DEPOSIT"))
                db.require(
                        status.equals("PENDING")
                                && booking.time("hold_expires_at") != null
                                && Instant.now().isBefore(booking.time("hold_expires_at"))
                                && amount.compareTo(
                                                booking.money("deposit_required_amount")
                                                        .min(balance.total().max(ZERO))
                                                        .subtract(balance.net())
                                                        .max(ZERO))
                                        <= 0,
                        "deposit");
            else {
                boolean advance =
                        status.equals("CHECKED_IN") && amount.compareTo(balance.debt()) > 0;
                if (advance) reason = db.required(reason, 1000);
                else db.require(amount.compareTo(balance.debt()) <= 0, "amount");
                if (status.equals("PENDING"))
                    db.require(
                            booking.time("hold_expires_at") != null
                                    && Instant.now().isBefore(booking.time("hold_expires_at")),
                            "state");
            }
        }
        Long session = null;
        if (method.equals("CASH")) {
            long employee = lockEmployee();
            var open =
                    db.rows(
                            "SELECT id FROM work_sessions WHERE employee_id=? AND closed_at IS NULL"
                                    + " FOR UPDATE",
                            employee);
            db.require(open.size() == 1, "shift");
            session = open.get(0).id("id");
        }
        long id =
                db.insert(
                        "payment_transactions",
                        "transaction_code,booking_id,parent_payment_id,kind,method,provider,idempotency_key,amount,currency,"
                            + "status,work_session_id,created_by,approved_by,reason,paid_at,provider_transaction_id",
                        code("PT"),
                        bookingId,
                        parent,
                        kind,
                        method,
                        "MANUAL",
                        key,
                        amount,
                        booking.s("currency"),
                        "SUCCEEDED",
                        session,
                        db.actor(),
                        refund ? db.actor() : null,
                        db.optional(reason, 1000),
                        ts(Instant.now()),
                        reference);
        db.event("PAYMENT:" + id, refund ? "PAYMENT_REFUNDED" : "PAYMENT_COLLECTED", bookingId);
        db.audit(
                "PAYMENT_" + kind,
                "PAYMENT",
                id,
                Map.of(),
                Map.of("bookingId", bookingId, "amount", amount, "method", method),
                reason);
        return id;
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','report.shift')")
    public void open(BigDecimal opening) {
        open(opening, null);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','report.shift')")
    public void open(BigDecimal opening, String notes) {
        long employee = lockEmployee();
        if (db.count(
                        "SELECT COUNT(*) FROM work_sessions WHERE employee_id=? AND closed_at IS"
                                + " NULL",
                        employee)
                > 0) return;
        long id =
                db.insert(
                        "work_sessions",
                        "employee_id,opening_cash,opened_at,notes",
                        employee,
                        money(opening, true),
                        ts(Instant.now()),
                        db.optional(notes, 1000));
        db.audit("SHIFT_OPEN", "WORK_SESSION", id, Map.of(), Map.of("employeeId", employee), null);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','report.shift')")
    public void close(BigDecimal closing) {
        close(closing, null);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','report.shift')")
    public void close(BigDecimal closing, String notes) {
        long employee = lockEmployee();
        var open =
                db.rows(
                        "SELECT * FROM work_sessions WHERE employee_id=? AND closed_at IS NULL FOR"
                                + " UPDATE",
                        employee);
        if (open.isEmpty()) return;
        var shift = open.get(0);
        long id = shift.id("id");
        BigDecimal counted = money(closing, true);
        BigDecimal expected =
                shift.money("opening_cash")
                        .add(
                                db.sum(
                                        """
                SELECT COALESCE(SUM(CASE WHEN kind='REFUND' THEN -amount ELSE amount END),0)
                FROM payment_transactions WHERE work_session_id=? AND method='CASH' AND status='SUCCEEDED'
                """,
                                        id));
        BigDecimal difference = counted.subtract(expected);
        String explanation =
                difference.signum() == 0 ? db.optional(notes, 1000) : db.required(notes, 1000);
        String savedNotes = Objects.toString(shift.s("notes"), "");
        if (explanation != null) savedNotes += "\nBàn giao / Handover: " + explanation;
        Instant now = Instant.now();
        Instant minimum = shift.time("opened_at").plusMillis(1);
        db.update(
                "UPDATE work_sessions SET closed_at=?,counted_closing_cash=?,notes=? WHERE id=?",
                ts(now.isAfter(minimum) ? now : minimum),
                counted,
                savedNotes,
                id);
        db.audit(
                "SHIFT_CLOSE",
                "WORK_SESSION",
                id,
                Map.of(),
                Map.of("countedCash", counted, "expectedCash", expected, "difference", difference),
                explanation);
    }

    public String refundReason(String reason) {
        reason = db.required(reason, 500);
        db.require(reason.length() >= 10, "refundReason");
        return reason;
    }

    private long lockEmployee() {
        var employee =
                db.rows(
                        "SELECT id FROM employees WHERE user_id=? AND ended_on IS NULL FOR UPDATE",
                        db.actor());
        db.require(employee.size() == 1, "employee");
        return employee.get(0).id("id");
    }

    public BigDecimal money(BigDecimal amount, boolean allowZero) {
        db.require(
                amount != null
                        && amount.scale() <= 2
                        && amount.precision() - amount.scale() <= 13
                        && (allowZero ? amount.signum() >= 0 : amount.signum() > 0)
                        && amount.compareTo(vnd(amount)) == 0,
                "amount");
        return vnd(amount);
    }
}
