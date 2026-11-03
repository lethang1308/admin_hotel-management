package com.example.hotelmanagement.modules.auth.service;

import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminLoginLifecycleService {
    private final UserRepository users;
    private final AuditLogService audit;

    @Transactional
    public void login(Long id) {
        users.findWithRolesById(id)
                .ifPresent(
                        user -> {
                            if (user.getStatus() == UserStatus.LOCKED && user.isActive()) {
                                var before =
                                        com.example.hotelmanagement.modules.audit.service
                                                .AuditSnapshots.user(user);
                                user.setStatus(UserStatus.ACTIVE);
                                user.setLockedUntil(null);
                                audit.record(
                                        "ACCOUNT_LOCK_EXPIRED",
                                        "USER",
                                        id,
                                        before,
                                        com.example.hotelmanagement.modules.audit.service
                                                .AuditSnapshots.user(user),
                                        "Timed lock expired");
                            }
                            user.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
                            audit.record(
                                    "LOGIN",
                                    "USER",
                                    id,
                                    null,
                                    Map.of("lastLoginAt", user.getLastLoginAt()),
                                    "CMS login");
                        });
    }

    @Transactional
    public void logout(Long id) {
        audit.record("LOGOUT", "USER", id, null, null, "CMS logout");
    }
}
