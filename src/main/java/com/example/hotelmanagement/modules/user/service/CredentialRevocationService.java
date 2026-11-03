package com.example.hotelmanagement.modules.user.service;

import com.example.hotelmanagement.modules.auth.repository.*;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class CredentialRevocationService {
    private final RefreshTokenRepository refreshTokens;
    private final AccountTokenRepository accountTokens;
    private final SessionRegistry sessions;
    private final jakarta.persistence.EntityManager entityManager;

    public void revoke(User user) {
        user.setSecurityVersion(
                Math.addExact(
                        user.getSecurityVersion() == null ? 0 : user.getSecurityVersion(), 1));
        // The existing refresh-token bulk update clears the persistence context.
        // Preserve account/profile changes before that update detaches entities.
        entityManager.flush();
        Instant now = Instant.now();
        refreshTokens.revokeAllActiveByUserId(user.getId(), now);
        accountTokens.invalidateAll(user.getId(), now);
        Runnable expire =
                () ->
                        sessions.getAllPrincipals().stream()
                                .filter(
                                        p ->
                                                p instanceof CustomUserDetails d
                                                        && d.getId().equals(user.getId()))
                                .forEach(
                                        p ->
                                                sessions.getAllSessions(p, false)
                                                        .forEach(s -> s.expireNow()));
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            expire.run();
                        }
                    });
        else expire.run();
    }
}
