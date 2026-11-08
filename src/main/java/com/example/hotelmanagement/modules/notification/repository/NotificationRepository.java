package com.example.hotelmanagement.modules.notification.repository;

import com.example.hotelmanagement.modules.notification.entity.Notification;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

import java.time.Instant;
import java.util.*;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query(
            "select n from Notification n where n.userId=:user and (n.type like 'BOOKING_%' or"
                + " n.type like 'PAYMENT_%') order by n.id desc")
    Page<Notification> owned(Long user, Pageable pageable);

    @Query(
            "select count(n) from Notification n where n.userId=:user and (n.type like 'BOOKING_%'"
                + " or n.type like 'PAYMENT_%') and n.readAt is null")
    long unread(Long user);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query(
            "update Notification n set n.readAt=:now where n.userId=:user and (n.type like"
                + " 'BOOKING_%' or n.type like 'PAYMENT_%') and n.readAt is null")
    int readAll(Long user, Instant now);
}
