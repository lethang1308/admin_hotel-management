package com.example.hotelmanagement.modules.notification.service;

import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('notification.manage')")
public class CustomerNotificationService {
    private final NotificationRepository notifications;

    public record Notice(
            Long id,
            Long bookingId,
            String type,
            String title,
            String body,
            Instant readAt,
            Instant createdAt) {}

    public PageResponse<Notice> list(long actor, int page, int size) {
        return PageResponse.from(
                notifications.owned(
                        actor, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)))),
                n ->
                        new Notice(
                                n.getId(),
                                n.getBookingId(),
                                n.getType(),
                                n.getTitle(),
                                n.getBody(),
                                n.getReadAt(),
                                n.getCreatedAt()));
    }

    public long unread(long actor) {
        return notifications.unread(actor);
    }

    @Transactional
    public void read(long actor, long id) {
        var n =
                notifications
                        .findByIdAndUserId(id, actor)
                        .filter(
                                x ->
                                        (x.getType().startsWith("BOOKING_")
                                                || x.getType().startsWith("PAYMENT_")))
                        .orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        if (n.getReadAt() == null) n.setReadAt(Instant.now());
    }

    @Transactional
    public void readAll(long actor) {
        notifications.readAll(actor, Instant.now());
    }
}
