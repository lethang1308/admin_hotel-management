package com.example.hotelmanagement.modules.reception;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class GuestMessageService {
    private final OperationsStore db;
    private final org.springframework.context.MessageSource translations;

    public record BookingEvent(long booking, String key, String type) {}

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void automatic(BookingEvent event) {
        String kind =
                switch (event.type()) {
                    case "BOOKING_CONFIRMED" -> "CONFIRMATION";
                    case "BOOKING_CHANGED",
                            "BOOKING_ROOM_ASSIGNED",
                            "BOOKING_ROOM_CHANGED",
                            "BOOKING_SPECIAL_REQUESTS_CHANGED" -> "CHANGE";
                    case "BOOKING_CANCELLED", "BOOKING_ROOM_CANCELLED" -> "CANCELLATION";
                    case "BOOKING_NO_SHOW", "BOOKING_ROOM_NO_SHOW" -> "NO_SHOW";
                    default -> null;
                };
        if (kind == null) return;
        var b = db.one("SELECT * FROM bookings WHERE id=?", event.booking());
        if (b.s("contact_email") == null || b.s("contact_email").isBlank()) return;
        enqueue(b, "EVENT:" + event.key(), kind, b.s("contact_email"), null);
    }

    @PreAuthorize("hasAuthority('notification.manage') and hasAuthority('booking.read.any')")
    public long resend(long booking, String key, String recipient) {
        var b = db.lockBooking(booking);
        db.require(key != null && key.matches("[A-F0-9]{24}"), "input");
        recipient = db.required(recipient, 150).toLowerCase(Locale.ROOT);
        try {
            email(recipient);
        } catch (IllegalArgumentException e) {
            db.require(false, "input");
        }
        var old = db.rows("SELECT * FROM guest_messages WHERE request_key=?", "MANUAL:" + key);
        if (!old.isEmpty()) {
            var m = old.get(0);
            db.require(
                    m.id("booking_id") == booking
                            && Objects.equals(m.id("created_by"), db.actor())
                            && m.s("recipient").equals(recipient),
                    "input");
            return m.id("id");
        }
        db.require(Set.of("CONFIRMED", "CHECKED_IN").contains(b.s("status")), "state");
        long id = enqueue(b, "MANUAL:" + key, "CONFIRMATION", recipient, db.actor());
        db.audit(
                "BOOKING_EMAIL_REQUESTED",
                "BOOKING",
                booking,
                Map.of(),
                Map.of("message", id),
                null);
        return id;
    }

    private long enqueue(Row b, String key, String type, String recipient, Long actor) {
        if (db.count("SELECT COUNT(*) FROM guest_messages WHERE request_key=?", key) > 0)
            return db.one("SELECT id FROM guest_messages WHERE request_key=?", key).id("id");
        // Invalid legacy addresses do not roll back an otherwise valid booking operation.
        try {
            email(recipient);
        } catch (IllegalArgumentException bad) {
            return 0;
        }
        long booking = b.id("id");
        StringBuilder text = new StringBuilder();
        var settings = db.settings();
        String heading =
                switch (type) {
                    case "CONFIRMATION" -> "Xác nhận đặt phòng";
                    case "CHANGE" -> "Cập nhật đặt phòng";
                    case "CANCELLATION" -> "Thông báo hủy đặt phòng";
                    default -> "Thông báo khách chưa đến";
                };
        String subject = heading + " · " + b.s("booking_code");
        text.append(settings.s("name"))
                .append("\n")
                .append(settings.s("address"))
                .append("\n")
                .append(Objects.toString(settings.s("hotline"), ""))
                .append("\n\n")
                .append("Kính gửi ")
                .append(b.s("contact_name"))
                .append(",\n")
                .append(heading)
                .append(": ")
                .append(b.s("booking_code"))
                .append("\nTrạng thái: ")
                .append(b.s("status"))
                .append("\n");
        var lines =
                db.rows(
                        "SELECT * FROM booking_rooms WHERE booking_id=? ORDER BY line_number",
                        booking);
        for (var line : lines)
            text.append("#")
                    .append(line.n("line_number"))
                    .append(" · ")
                    .append(line.s("room_type_name_snapshot"))
                    .append(" · ")
                    .append(line.date("check_in_date"))
                    .append(" → ")
                    .append(line.date("check_out_date"))
                    .append(" · ")
                    .append(line.n("adults"))
                    .append(" người lớn, ")
                    .append(line.n("children"))
                    .append(" trẻ em\n");
        for (var line : lines) {
            if (line.s("special_requests") != null)
                text.append("Yêu cầu phòng #")
                        .append(line.n("line_number"))
                        .append(": ")
                        .append(line.s("special_requests"))
                        .append("\n");
            var preferences =
                    db.rows(
                            "SELECT * FROM booking_room_preferences WHERE booking_room_id=?",
                            line.id("id"));
            if (!preferences.isEmpty()) {
                var pref = preferences.get(0);
                if (pref.s("tags") != null && !pref.s("tags").isBlank())
                    for (var tag : pref.s("tags").split(","))
                        text.append("• ")
                                .append(
                                        translations.getMessage(
                                                "care.tag." + tag,
                                                null,
                                                Locale.forLanguageTag("vi")))
                                .append("\n");
                if (pref.n("extra_beds") > 0)
                    text.append("Giường phụ: ").append(pref.n("extra_beds")).append("\n");
                if (pref.n("extra_guests") > 0)
                    text.append("Phụ phí người thêm: ")
                            .append(pref.n("extra_guests"))
                            .append(" đơn vị\n");
            }
        }
        text.append("\nCọc yêu cầu: ")
                .append(b.money("deposit_required_amount").toPlainString())
                .append(" VND\n");
        var policy =
                db.decode(
                        b.s("policy_snapshot"),
                        com.example.hotelmanagement.modules.pricing.dto.PricingModels.Hotel.class);
        var cancellation = policy.cancellation();
        text.append("Nhận phòng: ")
                .append(policy.checkIn())
                .append(" · Trả phòng: ")
                .append(policy.checkOut())
                .append(" · Múi giờ: ")
                .append(policy.timezone())
                .append("\n");
        text.append("Chính sách hủy: ")
                .append(cancellation.name())
                .append("\n")
                .append(Objects.toString(cancellation.description(), ""))
                .append("\n");
        text.append("Khách được tự hủy: ")
                .append(cancellation.customerCanCancel() ? "Có" : "Không")
                .append(" · Hạn trước nhận phòng: ")
                .append(cancellation.customerCancelDeadlineHours())
                .append(" giờ\n");
        for (var tier : cancellation.tiers())
            text.append("Trước giờ nhận phòng từ ")
                    .append(tier.minHoursBeforeCheckin())
                    .append(" giờ: phí ")
                    .append(tier.feeValue())
                    .append(" ")
                    .append(feeUnit(tier.feeType()))
                    .append("\n");
        text.append("Phí không đến: ")
                .append(cancellation.noShowFeeValue())
                .append(" ")
                .append(feeUnit(cancellation.noShowFeeType()))
                .append("\n");
        if (b.s("special_requests") != null)
            text.append("Yêu cầu đặc biệt: ").append(b.s("special_requests")).append("\n");
        String qr = null;
        if (type.equals("CONFIRMATION")
                && Set.of("CONFIRMED", "CHECKED_IN").contains(b.s("status"))) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Instant expiry =
                    lines.stream()
                            .map(x -> x.time("expected_check_out_at"))
                            .max(Comparator.naturalOrder())
                            .orElse(Instant.now())
                            .plus(Duration.ofDays(1));
            db.insert(
                    "booking_qr_tokens",
                    "booking_id,token_hash,expires_at",
                    booking,
                    QrCodeSupport.hash(token),
                    ts(expiry));
            qr = "HOTEL-BOOKING:" + token;
            text.append("\nVui lòng xuất trình QR đính kèm tại quầy. QR hết hạn: ")
                    .append(expiry.atZone(db.zone()))
                    .append(".\n");
        }
        text.append("\nĐây là thư tự động từ ").append(settings.s("name")).append(".");
        long id =
                db.insert(
                        "guest_messages",
                        "booking_id,request_key,message_type,recipient,subject,body,qr_payload,created_by",
                        booking,
                        key,
                        type,
                        recipient,
                        subject,
                        text.toString(),
                        qr,
                        actor);
        long outbox =
                db.insert(
                        "outbox_events",
                        "event_key,event_type,aggregate_type,aggregate_id,payload,available_at",
                        "GUEST-MESSAGE:" + id,
                        "GUEST_EMAIL",
                        "GUEST_MESSAGE",
                        id,
                        db.json(Map.of("message", id)),
                        ts(Instant.now()));
        Long user =
                db.one("SELECT user_id FROM customers WHERE id=?", b.id("customer_id"))
                        .id("user_id");
        Long notification =
                user == null
                        ? null
                        : db.insert(
                                "notifications",
                                "user_id,booking_id,outbox_event_id,deduplication_key,type,title,body",
                                user,
                                booking,
                                outbox,
                                "GUEST-MESSAGE:" + id,
                                "GUEST_" + type,
                                subject,
                                text.toString());
        db.insert(
                "notification_deliveries",
                "notification_id,outbox_event_id,channel,recipient_address,deduplication_key",
                notification,
                outbox,
                "EMAIL",
                recipient,
                "GUEST-MESSAGE:" + id);
        return id;
    }

    private String feeUnit(com.example.hotelmanagement.modules.policy.entity.FeeType type) {
        return switch (type) {
            case NONE -> "(miễn phí)";
            case PERCENT -> "% tiền phòng";
            case FIXED -> "VND";
            case FIRST_NIGHT -> "(theo tiền đêm đầu)";
        };
    }

    private void email(String value) {
        try {
            var address = new jakarta.mail.internet.InternetAddress(value, true);
            address.validate();
            if (value.contains("\r") || value.contains("\n") || !value.contains("@"))
                throw new IllegalArgumentException();
        } catch (jakarta.mail.internet.AddressException e) {
            throw new IllegalArgumentException("Invalid email");
        }
    }

    @PreAuthorize("hasAuthority('notification.manage') and hasAuthority('booking.read.any')")
    @Transactional(readOnly = true)
    public List<Row> history(long booking) {
        return db.rows(
                """
   SELECT m.id,m.message_type,m.recipient,m.subject,m.created_at,d.status,d.attempts,d.sent_at,d.next_retry_at,d.last_error
   FROM guest_messages m JOIN outbox_events o ON o.aggregate_type='GUEST_MESSAGE' AND o.aggregate_id=m.id
   JOIN notification_deliveries d ON d.outbox_event_id=o.id AND d.channel='EMAIL' WHERE m.booking_id=? ORDER BY m.id DESC LIMIT 100
   """,
                booking);
    }
}
