package com.example.hotelmanagement.modules.user.service;

import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.common.validation.StrongPasswordValidator;
import com.example.hotelmanagement.modules.audit.service.*;
import com.example.hotelmanagement.modules.employee.repository.EmployeeRepository;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AccountPasswordService {
    private final UserRepository users;
    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final CredentialRevocationService revocations;
    private final AuditLogService audit;
    private final CmsMessages messages;

    @Transactional
    public void changeOwn(Long id, String current, String next) {
        User u = required(id);
        if (current == null || !encoder.matches(current, u.getPasswordHash()))
            throw messages.error("cms.error.currentPassword");
        change(u, next, "PASSWORD_CHANGE", "Self-service password change");
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('account.manage')")
    public void resetEmployee(Long employeeId, String next, String reason) {
        var employee =
                employees
                        .findById(employeeId)
                        .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
        if (reason == null || reason.isBlank()) throw messages.error("cms.error.reason");
        if (reason.length() > 1000) throw messages.error("cms.error.reason");
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (auth != null && auth.getName().equalsIgnoreCase(employee.getUser().getEmail()))
            throw messages.error("cms.error.useOwnPassword");
        change(employee.getUser(), next, "PASSWORD_RESET", reason.trim());
    }

    private User required(Long id) {
        return users.findWithRolesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private void change(User u, String next, String action, String reason) {
        if (!new StrongPasswordValidator().isValid(next, null))
            throw messages.error("cms.error.strongPassword");
        if (encoder.matches(next, u.getPasswordHash()))
            throw messages.error("cms.error.samePassword");
        var before = AuditSnapshots.user(u);
        u.setPasswordHash(encoder.encode(next));
        u.setPasswordChangedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        revocations.revoke(u);
        users.save(u);
        audit.record(action, "USER", u.getId(), before, AuditSnapshots.user(u), reason);
    }
}
