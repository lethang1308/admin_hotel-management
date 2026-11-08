package com.example.hotelmanagement.modules.reception;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReceptionOutboxDispatcher {
    private final OperationsStore db;
    private final ApplicationEventPublisher events;

    public record Wake() {}

    public record Refresh() {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void dispatch() {
        var pending =
                db.rows(
                        """
                SELECT * FROM outbox_events WHERE status='PENDING' AND aggregate_type='BOOKING'
                AND available_at<=? AND (event_type LIKE 'BOOKING_%' OR event_type IN ('PAYMENT_COLLECTED','PAYMENT_REFUNDED'))
                ORDER BY id LIMIT 100 FOR UPDATE
                """,
                        ts(Instant.now()));
        if (pending.isEmpty()) return;
        var recipients =
                db.rows(
                        """
                SELECT u.id FROM users u WHERE (u.status='ACTIVE' OR (u.status='LOCKED' AND u.locked_until<=?))
                AND (u.locked_until IS NULL OR u.locked_until<=?)
                AND EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                    WHERE ur.user_id=u.id AND r.code IN ('ADMIN','RECEPTIONIST'))
                AND EXISTS(SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id
                    JOIN permissions p ON p.id=rp.permission_id
                    WHERE ur.user_id=u.id AND p.code IN ('notification.manage','booking.read.any'))
                ORDER BY u.id
                """,
                        ts(Instant.now()),
                        ts(Instant.now()));
        for (var event : pending) {
            long booking = event.id("aggregate_id");
            var order = db.one("SELECT booking_code FROM bookings WHERE id=?", booking);
            for (var user : recipients) {
                if ("BOOKING_REFUND_REQUESTED".equals(event.s("event_type"))
                        && db.count(
                                        "SELECT COUNT(*) FROM user_roles ur JOIN role_permissions"
                                            + " rp ON rp.role_id=ur.role_id JOIN permissions p ON"
                                            + " p.id=rp.permission_id WHERE ur.user_id=? AND"
                                            + " p.code='payment.refund'",
                                        user.id("id"))
                                == 0) continue;
                if ("BOOKING_REFUND_RESOLVED".equals(event.s("event_type"))) {
                    var payload = db.decode(event.s("payload"), Map.class);
                    if (!Objects.toString(user.id("id"))
                            .equals(Objects.toString(payload.get("recipientUserId")))) continue;
                }
                String key = "RECEPTION:" + event.id("id") + ":" + user.id("id");
                if (db.count("SELECT COUNT(*) FROM notifications WHERE deduplication_key=?", key)
                        > 0) continue;
                long id =
                        db.insert(
                                "notifications",
                                "user_id,booking_id,outbox_event_id,deduplication_key,type,title,body,created_at",
                                user.id("id"),
                                booking,
                                event.id("id"),
                                key,
                                "RECEPTION_" + event.s("event_type"),
                                order.s("booking_code"),
                                event.s("event_type"),
                                ts(Instant.now()));
                db.insert(
                        "notification_deliveries",
                        "notification_id,outbox_event_id,channel,recipient_address,deduplication_key,status,attempts,sent_at",
                        id,
                        event.id("id"),
                        "IN_APP",
                        "USER:" + user.id("id"),
                        key,
                        "SENT",
                        1,
                        ts(Instant.now()));
            }
            db.update(
                    "UPDATE outbox_events SET status='PROCESSED',processed_at=?,attempts=attempts+1"
                            + " WHERE id=?",
                    ts(Instant.now()),
                    event.id("id"));
        }
        events.publishEvent(new Refresh());
    }
}
