package com.example.hotelmanagement.modules.auth.service;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.util.TokenHashUtils;
import com.example.hotelmanagement.modules.audit.service.*;
import com.example.hotelmanagement.modules.auth.entity.AccountToken;
import com.example.hotelmanagement.modules.auth.repository.AccountTokenRepository;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.modules.user.service.CredentialRevocationService;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private final jakarta.persistence.EntityManager entityManager;
    private final UserRepository users;
    private final AccountTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final CredentialRevocationService revocations;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;

    public record Requested(String recipient, String token) {}

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void request(String email) {
        var found = users.findByEmailIgnoreCase(email.strip().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) return;
        var user = users.lockById(found.get().getId()).orElseThrow();
        entityManager.refresh(user, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!user.isActive()) return;
        Instant now = Instant.now();
        if (tokens.countByUserIdAndPurposeAndCreatedAtAfter(
                        user.getId(), "PASSWORD_RESET", now.minusSeconds(60))
                > 0) return;
        tokens.invalidatePurpose(user.getId(), "PASSWORD_RESET", now);
        String raw = TokenHashUtils.generateOpaqueToken();
        var token = new AccountToken();
        token.setUserId(user.getId());
        token.setPurpose("PASSWORD_RESET");
        token.setTokenHash(TokenHashUtils.sha256Hex(raw));
        token.setExpiresAt(now.plusSeconds(1800));
        token.setCreatedAt(now);
        tokens.saveAndFlush(token);
        events.publishEvent(new Requested(user.getEmail(), raw));
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reset(String raw, String password) {
        String hash = TokenHashUtils.sha256Hex(raw);
        var initial =
                tokens.findByTokenHashAndPurpose(hash, "PASSWORD_RESET").orElseThrow(this::invalid);
        var user = users.lockById(initial.getUserId()).orElseThrow(this::invalid);
        var token = tokens.lockById(initial.getId()).orElseThrow(this::invalid);
        entityManager.refresh(user, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(token, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        // Current locking reads are required on MySQL: an earlier consistent read can be stale.
        // Always lock user before token to match credential revocation ordering.
        if (token.getUsedAt() != null
                || !token.getExpiresAt().isAfter(Instant.now())
                || !user.isActive()) throw invalid();
        if (!new com.example.hotelmanagement.common.validation.StrongPasswordValidator()
                .isValid(password, null))
            throw new BusinessException(
                    "Mật khẩu cần ít nhất 8 ký tự, có chữ và số, tối đa 72 byte UTF-8.");
        if (encoder.matches(password, user.getPasswordHash()))
            throw new BusinessException("Mật khẩu mới phải khác mật khẩu cũ.");
        var before = AuditSnapshots.user(user);
        token.setUsedAt(Instant.now());
        tokens.saveAndFlush(token);
        user.setPasswordHash(encoder.encode(password));
        user.setPasswordChangedAt(Instant.now());
        revocations.revoke(user);
        users.save(user);
        audit.record(
                "PASSWORD_RESET_SELF",
                "USER",
                user.getId(),
                before,
                AuditSnapshots.user(user),
                "Verified one-time email reset");
    }

    private BusinessException invalid() {
        return new BusinessException("Liên kết khôi phục mật khẩu không hợp lệ hoặc đã hết hạn.");
    }
}
