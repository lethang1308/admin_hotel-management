package com.example.hotelmanagement.modules.audit.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotelmanagement.modules.audit.entity.AuditLog;
import com.example.hotelmanagement.modules.audit.event.AuditRecordEvent;
import com.example.hotelmanagement.modules.audit.repository.AuditLogRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class AuditLogServiceTest {
    @Test
    void redactsSensitiveKeysRecursivelyAndKeepsSafeChanges() {
        var repository = mock(AuditLogRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var service = new AuditLogService(repository, publisher, JsonMapper.builder().build());
        var nested =
                Map.of(
                        "password",
                        "never-log-password",
                        "refresh_token",
                        "never-log-token",
                        "document_number",
                        "never-log-identity",
                        "name",
                        "Safe Name");
        service.record(
                "TEST",
                "USER",
                12L,
                null,
                Map.of(
                        "name",
                        "Updated",
                        "nested",
                        nested,
                        "items",
                        List.of(nested),
                        "password_hash",
                        "never-log-hash"),
                "Reason");
        var event = ArgumentCaptor.forClass(AuditRecordEvent.class);
        verify(publisher).publishEvent(event.capture());
        service.persist(event.getValue());
        var row = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getAfterData())
                .contains("Safe Name", "Updated")
                .doesNotContain("never-log", "password_hash", "document_number");
        assertThat(row.getValue().getEntityId()).isEqualTo("12");
    }
}
