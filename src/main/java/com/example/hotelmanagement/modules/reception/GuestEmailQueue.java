package com.example.hotelmanagement.modules.reception;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GuestEmailQueue {
    private final OperationsStore db;

    public record Job(
            long outbox,
            long message,
            String worker,
            String recipient,
            String subject,
            String body,
            String qr) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Job claim() {
        Instant now = Instant.now();
        db.update(
                "UPDATE notification_deliveries SET"
                    + " status='FAILED',next_retry_at=NULL,last_error='Email cần kiểm tra sau khi"
                    + " worker hết hạn xử lý' WHERE outbox_event_id IN (SELECT id FROM"
                    + " outbox_events WHERE aggregate_type='GUEST_MESSAGE' AND status='PROCESSING'"
                    + " AND attempts>=5 AND locked_until<?)",
                ts(now));
        db.update(
                "UPDATE outbox_events SET"
                    + " status='FAILED',locked_until=NULL,worker_id=NULL,last_error='Worker lease"
                    + " expired' WHERE aggregate_type='GUEST_MESSAGE' AND status='PROCESSING' AND"
                    + " attempts>=5 AND locked_until<?",
                ts(now));
        var jobs =
                db.rows(
                        """
   SELECT * FROM outbox_events WHERE aggregate_type='GUEST_MESSAGE' AND attempts<5
   AND ((status='PENDING' AND available_at<=?) OR (status='PROCESSING' AND locked_until<?))
   ORDER BY id LIMIT 1 FOR UPDATE
   """,
                        ts(now),
                        ts(now));
        if (jobs.isEmpty()) return null;
        var job = jobs.get(0);
        String worker = UUID.randomUUID().toString();
        db.update(
                "UPDATE outbox_events SET"
                    + " status='PROCESSING',attempts=attempts+1,worker_id=?,locked_until=? WHERE"
                    + " id=?",
                worker,
                ts(now.plusSeconds(60)),
                job.id("id"));
        db.update(
                "UPDATE notification_deliveries SET"
                    + " status='PENDING',attempts=attempts+1,next_retry_at=NULL WHERE"
                    + " outbox_event_id=? AND channel='EMAIL'",
                job.id("id"));
        var m = db.one("SELECT * FROM guest_messages WHERE id=?", job.id("aggregate_id"));
        return new Job(
                job.id("id"),
                m.id("id"),
                worker,
                m.s("recipient"),
                m.s("subject"),
                m.s("body"),
                m.s("qr_payload"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void finish(Job job, boolean sent) {
        var o = db.one("SELECT * FROM outbox_events WHERE id=? FOR UPDATE", job.outbox());
        if (!Objects.equals(o.s("worker_id"), job.worker()) || !o.s("status").equals("PROCESSING"))
            return;
        String error =
                sent ? null : "SMTP gửi chưa thành công; kiểm tra cấu hình hoặc máy chủ thư.";
        boolean terminal = !sent && o.n("attempts") >= 5;
        Instant retry = Instant.now().plusSeconds(60L * (1L << Math.min(o.n("attempts"), 5)));
        db.update(
                "UPDATE outbox_events SET"
                    + " status=?,processed_at=?,available_at=?,locked_until=NULL,worker_id=NULL,last_error=?"
                    + " WHERE id=?",
                sent ? "PROCESSED" : terminal ? "FAILED" : "PENDING",
                sent ? ts(Instant.now()) : null,
                ts(retry),
                error,
                job.outbox());
        db.update(
                "UPDATE notification_deliveries SET status=?,sent_at=?,next_retry_at=?,last_error=?"
                    + " WHERE outbox_event_id=? AND channel='EMAIL'",
                sent ? "SENT" : "FAILED",
                sent ? ts(Instant.now()) : null,
                !sent && !terminal ? ts(retry) : null,
                error,
                job.outbox());
    }
}
