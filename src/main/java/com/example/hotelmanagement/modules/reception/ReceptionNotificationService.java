package com.example.hotelmanagement.modules.reception;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
@PreAuthorize("hasAnyAuthority('notification.manage','booking.read.any')")
public class ReceptionNotificationService {
    private final OperationsStore db;

    public record Notice(
            long id, Long bookingId, String code, String type, Instant createdAt, boolean read) {}

    public record Feed(long unread, long version, List<Notice> items) {}

    @Transactional(readOnly = true)
    public Feed feed() {
        return feedFor(db.actor());
    }

    Feed feedFor(long user) {
        var summary =
                db.one(
                        """
                SELECT COALESCE(MAX(id),0) AS version,COALESCE(SUM(CASE WHEN read_at IS NULL THEN 1 ELSE 0 END),0) AS unread
                FROM notifications WHERE user_id=? AND type LIKE 'RECEPTION_%'
                """,
                        user);
        var rows =
                db.rows(
                        """
                SELECT n.id,n.booking_id,n.type,n.created_at,n.read_at,b.booking_code FROM notifications n
                LEFT JOIN bookings b ON b.id=n.booking_id WHERE n.user_id=? AND n.type LIKE 'RECEPTION_%'
                ORDER BY n.id DESC LIMIT 20
                """,
                        user);
        return new Feed(
                summary.id("unread"),
                summary.id("version"),
                rows.stream()
                        .map(
                                n ->
                                        new Notice(
                                                n.id("id"),
                                                n.id("booking_id"),
                                                n.s("booking_code"),
                                                n.s("type").substring(10),
                                                n.time("created_at"),
                                                n.time("read_at") != null))
                        .toList());
    }

    public void read(List<Long> ids) {
        db.require(
                ids != null
                        && !ids.isEmpty()
                        && ids.size() <= 100
                        && ids.stream().allMatch(Objects::nonNull),
                "input");
        var now = ts(Instant.now());
        for (long id : new HashSet<>(ids))
            db.update(
                    "UPDATE notifications SET read_at=? WHERE id=? AND user_id=? AND read_at IS"
                            + " NULL",
                    now,
                    id,
                    db.actor());
    }
}
