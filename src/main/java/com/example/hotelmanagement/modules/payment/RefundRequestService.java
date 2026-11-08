package com.example.hotelmanagement.modules.payment;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RefundRequestService {
    private final OperationsStore db;
    private final FrontDeskPaymentService payments;
    private final BookingLedger ledger;

    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public long request(
            long booking,
            String key,
            long parent,
            BigDecimal amount,
            String method,
            String reason) {
        db.lockBooking(booking);
        reason = payments.refundReason(reason);
        db.require(key != null && key.matches("[A-F0-9]{24}"), "input");
        amount = payments.money(amount, false);
        db.require(Set.of("CASH", "BANK_TRANSFER").contains(Objects.toString(method, "")), "input");
        var old = db.rows("SELECT * FROM refund_requests WHERE request_key=?", key);
        if (!old.isEmpty()) {
            var r = old.get(0);
            db.require(
                    r.id("booking_id") == booking
                            && r.id("requested_by").equals(db.actor())
                            && r.id("parent_payment_id") == parent
                            && r.money("amount").compareTo(amount) == 0
                            && r.s("method").equals(method)
                            && r.s("reason").equals(reason),
                    "idempotency");
            return r.id("id");
        }
        limit(booking, parent, amount);
        long id =
                db.insert(
                        "refund_requests",
                        "booking_id,parent_payment_id,request_key,amount,method,reason,requested_by",
                        booking,
                        parent,
                        key,
                        amount,
                        method,
                        reason,
                        db.actor());
        db.audit(
                "REFUND_REQUEST_CREATE",
                "BOOKING",
                booking,
                Map.of(),
                Map.of("request", id, "amount", amount),
                reason);
        db.event("REFUND-REQUEST:" + id, "BOOKING_REFUND_REQUESTED", booking);
        return id;
    }

    @PreAuthorize("hasAuthority('payment.refund')")
    public Long decide(long booking, long id, boolean approve, String note, String reference) {
        db.lockBooking(booking);
        var r =
                db.one(
                        "SELECT * FROM refund_requests WHERE id=? AND booking_id=? FOR UPDATE",
                        id,
                        booking);
        if (!"PENDING".equals(r.s("status"))) {
            db.require(
                    approve && "COMPLETED".equals(r.s("status"))
                            || !approve && "REJECTED".equals(r.s("status")),
                    "state");
            return r.id("payment_transaction_id");
        }
        note = payments.refundReason(note);
        Long payment = null;
        if (approve) {
            limit(booking, r.id("parent_payment_id"), r.money("amount"));
            // Approval records money already returned, never calls a banking API.
            payment =
                    payments.refundReferenced(
                            booking,
                            codeFor(id),
                            r.id("parent_payment_id"),
                            r.s("method"),
                            r.money("amount"),
                            note,
                            reference);
        }
        db.update(
                "UPDATE refund_requests SET"
                    + " status=?,handled_by=?,handled_at=?,response_note=?,payment_transaction_id=?"
                    + " WHERE id=?",
                approve ? "COMPLETED" : "REJECTED",
                db.actor(),
                ts(Instant.now()),
                note,
                payment,
                id);
        db.audit(
                approve ? "REFUND_REQUEST_COMPLETED" : "REFUND_REQUEST_REJECTED",
                "BOOKING",
                booking,
                Map.of("request", id),
                Map.of("amount", r.money("amount")),
                note);
        String key = "REFUND-DECISION:" + id;
        db.event(key, "BOOKING_REFUND_RESOLVED", booking);
        db.update(
                "UPDATE outbox_events SET payload=? WHERE event_key=?",
                db.json(Map.of("bookingId", booking, "recipientUserId", r.id("requested_by"))),
                key);
        return payment;
    }

    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public void withdraw(long booking, long id) {
        db.lockBooking(booking);
        var r =
                db.one(
                        "SELECT * FROM refund_requests WHERE id=? AND booking_id=? FOR UPDATE",
                        id,
                        booking);
        if (!r.id("requested_by").equals(db.actor()))
            throw new AccessDeniedException("Not your request");
        if ("WITHDRAWN".equals(r.s("status"))) return;
        db.require("PENDING".equals(r.s("status")), "state");
        db.update(
                "UPDATE refund_requests SET status='WITHDRAWN',handled_by=?,handled_at=? WHERE"
                    + " id=?",
                db.actor(),
                ts(Instant.now()),
                id);
        db.audit(
                "REFUND_REQUEST_WITHDRAWN",
                "BOOKING",
                booking,
                Map.of("request", id),
                Map.of(),
                null);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        boolean manager =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("payment.refund"));
        return db
                .rows(
                        """
            SELECT r.id,r.booking_id,r.amount,r.method,r.reason,r.status,r.created_at,b.booking_code,b.contact_name,u.email AS requester
            FROM refund_requests r JOIN bookings b ON b.id=r.booking_id JOIN users u ON u.id=r.requested_by
            WHERE (?=TRUE OR r.requested_by=?) AND r.status='PENDING' ORDER BY r.created_at,r.id LIMIT 100
            """,
                        manager,
                        db.actor())
                .stream()
                .map(Row::data)
                .toList();
    }

    private void limit(long booking, long parent, BigDecimal amount) {
        var p =
                db.one(
                        "SELECT * FROM payment_transactions WHERE id=? AND booking_id=?",
                        parent,
                        booking);
        BigDecimal used =
                db.sum(
                        "SELECT COALESCE(SUM(amount),0) FROM payment_transactions WHERE"
                            + " parent_payment_id=? AND kind='REFUND' AND status IN"
                            + " ('PENDING','SUCCEEDED')",
                        parent);
        db.require(
                "SUCCEEDED".equals(p.s("status"))
                        && !"REFUND".equals(p.s("kind"))
                        && amount.compareTo(p.money("amount").subtract(used)) <= 0
                        && amount.compareTo(ledger.balance(booking).refundDue()) <= 0,
                "refund");
    }

    private String codeFor(long id) {
        return com.example.hotelmanagement.modules.reception.QrCodeSupport.hash(
                        "REFUND_REQUEST:" + id)
                .substring(0, 24)
                .toUpperCase(Locale.ROOT);
    }
}
