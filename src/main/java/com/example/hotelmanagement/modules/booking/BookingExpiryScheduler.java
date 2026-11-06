package com.example.hotelmanagement.modules.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "app.hotel.calendar-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BookingExpiryScheduler {
    private final OperationsStore db;
    private final BookingService bookings;

    @Scheduled(fixedDelayString = "${app.booking.expiry-interval-ms:60000}")
    public void expire() {
        for (var row :
                db.rows(
                        """
                SELECT id FROM bookings WHERE status='PENDING' AND hold_expires_at<=?
                ORDER BY hold_expires_at LIMIT 100
                """,
                        OperationsStore.ts(Instant.now()))) {
            try {
                bookings.expire(row.id("id"));
            } catch (RuntimeException error) {
                log.error("Could not expire booking {}", row.id("id"), error);
            }
        }
    }
}
