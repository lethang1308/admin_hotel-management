package com.example.hotelmanagement.config;

import com.example.hotelmanagement.modules.room.service.RoomCalendarService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "app.hotel.calendar-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RoomCalendarScheduler {
    private final RoomCalendarService calendar;

    @Scheduled(
            fixedDelayString = "${app.hotel.calendar-delay-ms:60000}",
            initialDelayString = "${app.hotel.calendar-delay-ms:60000}")
    public void synchronize() {
        try {
            calendar.synchronize();
        } catch (Exception ex) {
            log.error("Room calendar synchronization failed", ex);
        }
    }
}
