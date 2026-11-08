package com.example.hotelmanagement.modules.notification.service;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.reception.GuestMessageService.BookingEvent;

import lombok.RequiredArgsConstructor;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Component
@RequiredArgsConstructor
public class CustomerNotificationEvents {
    private final OperationsStore db;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void booking(BookingEvent event) {
        String label =
                switch (event.type()) {
                    case "BOOKING_CREATED" -> "Đã tạo đơn đặt phòng";
                    case "BOOKING_CONFIRMED" -> "Đặt phòng đã được xác nhận";
                    case "BOOKING_CANCELLED" -> "Đặt phòng đã được hủy";
                    case "BOOKING_CHANGED" -> "Đặt phòng đã được điều chỉnh";
                    case "BOOKING_NO_SHOW" -> "Thông báo khách chưa đến";
                    case "PAYMENT_COLLECTED" -> "Khách sạn đã ghi nhận thanh toán";
                    case "PAYMENT_REFUNDED" -> "Khách sạn đã ghi nhận hoàn tiền";
                    default -> null;
                };
        if (label == null) return;
        var booking =
                db.one(
                        "SELECT b.booking_code,c.user_id FROM bookings b JOIN customers c ON"
                            + " c.id=b.customer_id WHERE b.id=?",
                        event.booking());
        Long user = booking.id("user_id");
        if (user == null) return;
        String key = "CUSTOMER:" + event.key() + ":" + user;
        if (db.count("SELECT COUNT(*) FROM notifications WHERE deduplication_key=?", key) > 0)
            return;
        long outbox =
                db.one("SELECT id FROM outbox_events WHERE event_key=?", event.key()).id("id");
        String body = label + " · " + booking.s("booking_code");
        if (event.type().startsWith("PAYMENT_") && event.key().matches("PAYMENT:[0-9]+")) {
            var payment =
                    db.one(
                            "SELECT amount FROM payment_transactions WHERE id=? AND booking_id=?",
                            Long.parseLong(event.key().substring(8)),
                            event.booking());
            body =
                    label
                            + " "
                            + java.text.NumberFormat.getNumberInstance(
                                            Locale.forLanguageTag("vi-VN"))
                                    .format(payment.money("amount"))
                            + " VND · "
                            + booking.s("booking_code");
        }
        db.insert(
                "notifications",
                "user_id,booking_id,outbox_event_id,deduplication_key,type,title,body",
                user,
                event.booking(),
                outbox,
                key,
                event.type(),
                label,
                body);
        db.insert(
                "notification_deliveries",
                "notification_id,outbox_event_id,channel,recipient_address,deduplication_key,status,attempts,sent_at",
                db.one("SELECT id FROM notifications WHERE deduplication_key=?", key).id("id"),
                outbox,
                "IN_APP",
                "USER:" + user,
                key,
                "SENT",
                1,
                OperationsStore.ts(java.time.Instant.now()));
    }
}
