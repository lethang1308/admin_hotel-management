package com.example.hotelmanagement.modules.employee.service;

import com.example.hotelmanagement.common.exception.*;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.common.validation.StrongPasswordValidator;
import com.example.hotelmanagement.modules.audit.service.*;
import com.example.hotelmanagement.modules.employee.dto.*;
import com.example.hotelmanagement.modules.employee.entity.Employee;
import com.example.hotelmanagement.modules.employee.repository.EmployeeRepository;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.entity.*;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.modules.user.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('account.manage')")
public class EmployeeService {
    private final EmployeeRepository repository;
    private final UserRepository users;
    private final UserService userService;
    private final RoleService roles;
    private final CredentialRevocationService revocations;
    private final AuditLogService audit;
    private final CmsMessages messages;

    public PageResponse<EmployeeResponse> search(
            int page, int size, String search, UserStatus status, String position) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String pattern =
                "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return PageResponse.from(
                repository.findAll(
                        (r, q, cb) -> {
                            var p = new ArrayList<jakarta.persistence.criteria.Predicate>();
                            var user = r.join("user");
                            if (!term.isEmpty())
                                p.add(
                                        cb.or(
                                                cb.like(
                                                        cb.lower(r.get("employeeCode")),
                                                        pattern,
                                                        '\\'),
                                                cb.like(
                                                        cb.lower(user.get("fullName")),
                                                        pattern,
                                                        '\\'),
                                                cb.like(cb.lower(user.get("email")), pattern, '\\'),
                                                cb.like(cb.lower(user.get("phone")), pattern, '\\'),
                                                cb.like(
                                                        cb.lower(r.get("position")),
                                                        pattern,
                                                        '\\')));
                            if (status != null) p.add(cb.equal(user.get("status"), status));
                            if (position != null && !position.isBlank())
                                p.add(
                                        cb.like(
                                                cb.lower(r.get("position")),
                                                "%"
                                                        + position.trim().toLowerCase(Locale.ROOT)
                                                        + "%"));
                            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
                        },
                        PageRequest.of(
                                Math.max(0, page),
                                Math.min(100, Math.max(1, size)),
                                Sort.by(Sort.Direction.DESC, "id"))),
                this::view);
    }

    public EmployeeResponse get(Long id) {
        return view(required(id));
    }

    public EmployeeForm form(Long id) {
        Employee e = required(id);
        EmployeeForm f = new EmployeeForm();
        f.setEmployeeCode(e.getEmployeeCode());
        f.setFullName(e.getUser().getFullName());
        f.setEmail(e.getUser().getEmail());
        f.setPhone(e.getUser().getPhone());
        f.setPosition(e.getPosition());
        f.setHiredOn(e.getHiredOn());
        f.setEndedOn(e.getEndedOn());
        return f;
    }

    @Transactional
    public Long create(EmployeeForm f) {
        validate(f);
        if (!new StrongPasswordValidator().isValid(f.getPassword(), null))
            throw messages.error("cms.error.strongPassword");
        if (users.existsByEmailIgnoreCase(f.getEmail().trim().toLowerCase(Locale.ROOT)))
            throw messages.error("cms.error.emailDuplicate");
        String phone = phone(f.getPhone());
        if (phone != null && users.existsByPhone(phone))
            throw messages.error("cms.error.phoneDuplicate");
        var response =
                userService.createUser(
                        CreateUserRequest.builder()
                                .fullName(f.getFullName())
                                .email(f.getEmail())
                                .password(f.getPassword())
                                .status(UserStatus.ACTIVE)
                                .roles(Set.of("RECEPTIONIST"))
                                .build());
        Employee e = repository.findByUser_Id(response.getId()).orElseThrow();
        apply(e, f);
        e.getUser().setPhone(phone);
        repository.flush();
        audit.record(
                "EMPLOYEE_CREATE",
                "EMPLOYEE",
                e.getId(),
                null,
                AuditSnapshots.employee(e),
                f.getReason());
        return e.getId();
    }

    @Transactional
    public void update(Long id, EmployeeForm f) {
        roles.lockAdminRole();
        Employee e = required(id);
        guardAdmin(e);
        validate(f);
        var before = AuditSnapshots.employee(e);
        User u = e.getUser();
        String email = f.getEmail().trim().toLowerCase(Locale.ROOT), phone = phone(f.getPhone());
        if (users.existsByEmailIgnoreCaseAndIdNot(email, u.getId()))
            throw messages.error("cms.error.emailDuplicate");
        if (phone != null && users.existsByPhoneAndIdNot(phone, u.getId()))
            throw messages.error("cms.error.phoneDuplicate");
        boolean changedEmail = !email.equals(u.getEmail());
        u.setFullName(f.getFullName().trim());
        u.setEmail(email);
        u.setPhone(phone);
        apply(e, f);
        if (changedEmail) revocations.revoke(u);
        audit.record(
                "EMPLOYEE_UPDATE",
                "EMPLOYEE",
                id,
                before,
                AuditSnapshots.employee(e),
                f.getReason());
    }

    @Transactional
    public void status(
            Long id, UserStatus status, Instant until, String reason, String actorEmail) {
        roles.lockAdminRole();
        Employee e = required(id);
        guardAdmin(e);
        userService.updateAccountStatus(e.getUser().getId(), status, until, reason, actorEmail);
    }

    private void apply(Employee e, EmployeeForm f) {
        if (f.getEmployeeCode() != null && !f.getEmployeeCode().isBlank()) {
            String code = f.getEmployeeCode().trim().toUpperCase(Locale.ROOT);
            if (repository.existsByEmployeeCodeAndIdNot(code, e.getId()))
                throw messages.error("cms.error.employeeCode");
            e.setEmployeeCode(code);
        }
        e.setPosition(f.getPosition() == null ? null : f.getPosition().trim());
        e.setHiredOn(f.getHiredOn());
        e.setEndedOn(f.getEndedOn());
    }

    private void validate(EmployeeForm f) {
        if (f.getHiredOn() != null
                && f.getEndedOn() != null
                && f.getEndedOn().isBefore(f.getHiredOn()))
            throw messages.error("cms.error.workDates");
    }

    private void guardAdmin(Employee e) {
        if (e.getUser().hasRole("ADMIN")
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            throw new AccessDeniedException("Protected administrator account");
    }

    private String phone(String p) {
        return p == null || p.isBlank() ? null : p.replaceAll("[ .()-]", "");
    }

    private Employee required(Long id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    private EmployeeResponse view(Employee e) {
        User u = e.getUser();
        return new EmployeeResponse(
                e.getId(),
                u.getId(),
                e.getEmployeeCode(),
                u.getFullName(),
                u.getEmail(),
                u.getPhone(),
                e.getPosition(),
                e.getHiredOn(),
                e.getEndedOn(),
                u.getStatus(),
                u.getLockedUntil(),
                u.getLastLoginAt(),
                u.getRoles().stream()
                        .map(r -> r.getCode())
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
