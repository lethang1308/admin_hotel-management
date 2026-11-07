package com.example.hotelmanagement.modules.reception;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.security.authentication.*;

import jakarta.servlet.http.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReceptionNotificationPublisher {
    private final CustomUserDetailsService users;
    private final ReceptionOutboxDispatcher dispatcher;
    private final OperationsStore db;
    private final Map<SseEmitter, Connection> clients = new ConcurrentHashMap<>();

    @Value("${app.reception.notifications-enabled:true}")
    private boolean enabled;

    private record Connection(long user, long securityVersion, HttpSession session) {}

    public SseEmitter subscribe(CustomUserDetails user, HttpSession session) {
        if (clients.size() >= 200
                || clients.values().stream().filter(c -> c.user() == user.getId()).count() >= 4)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        var emitter = new SseEmitter(120000L);
        clients.put(emitter, new Connection(user.getId(), user.getSecurityVersion(), session));
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(
                () -> {
                    clients.remove(emitter);
                    emitter.complete();
                });
        emitter.onError(error -> clients.remove(emitter));
        send(emitter, clients.get(emitter));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void wake(ReceptionOutboxDispatcher.Wake wake) {
        dispatch();
    }

    @Scheduled(fixedDelayString = "${app.reception.dispatch-interval-ms:5000}")
    public void dispatch() {
        if (!enabled) return;
        try {
            dispatcher.dispatch();
        } catch (RuntimeException error) {
            log.warn("Reception notifications will retry", error);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void refresh(ReceptionOutboxDispatcher.Refresh event) {
        heartbeat();
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        clients.forEach(this::send);
    }

    private void send(SseEmitter emitter, Connection connection) {
        try {
            Object savedContext = connection.session().getAttribute("SPRING_SECURITY_CONTEXT");
            if (!(savedContext
                            instanceof
                            org.springframework.security.core.context.SecurityContext
                            context)
                    || context.getAuthentication() == null
                    || !(context.getAuthentication().getPrincipal()
                            instanceof CustomUserDetails savedUser)
                    || !Objects.equals(savedUser.getId(), connection.user())) {
                close(emitter);
                return;
            }
            var fresh = users.loadUserById(connection.user());
            var authorities = fresh.getAuthorities().stream().map(Object::toString).toList();
            boolean staff =
                    authorities.contains("ROLE_ADMIN") || authorities.contains("ROLE_RECEPTIONIST");
            if (!fresh.isEnabled()
                    || !fresh.isAccountNonLocked()
                    || fresh.getSecurityVersion() != connection.securityVersion()
                    || !staff
                    || (!authorities.contains("notification.manage")
                            && !authorities.contains("booking.read.any"))) {
                close(emitter);
                return;
            }
            long version =
                    db.count(
                            "SELECT COALESCE(MAX(id),0) FROM notifications WHERE user_id=? AND type"
                                    + " LIKE 'RECEPTION_%'",
                            connection.user());
            emitter.send(SseEmitter.event().name("refresh").data(Map.of("version", version)));
        } catch (IOException disconnected) {
            // The servlet container completes the failed asynchronous response.
            clients.remove(emitter);
        } catch (RuntimeException failure) {
            close(emitter);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        clients.keySet().forEach(this::close);
    }

    private void close(SseEmitter emitter) {
        clients.remove(emitter);
        emitter.complete();
    }
}
