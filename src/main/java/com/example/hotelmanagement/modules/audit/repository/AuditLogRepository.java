package com.example.hotelmanagement.modules.audit.repository;

import com.example.hotelmanagement.modules.audit.entity.AuditLog;

import org.springframework.data.jpa.repository.*;

public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {}
