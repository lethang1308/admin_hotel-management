package com.example.hotelmanagement.modules.user.service;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.DuplicateResourceException;
import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.common.pagination.PaginationUtils;
import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.audit.service.AuditSnapshots;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserStatusRequest;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.mapper.UserMapper;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.modules.user.repository.UserSpecifications;
import com.example.hotelmanagement.security.authorization.SecurityConstants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "fullName", "email", "status", "createdAt", "updatedAt");

    private final UserRepository userRepository;
    private final RoleService roleService;
    private final CredentialRevocationService revocations;
    private final ProfileProvisioningService profiles;
    private final AuditLogService audit;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AppProperties appProperties;
    private final com.example.hotelmanagement.common.util.CmsMessages messages;

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> getUsers(
            int page, int size, String sort, String search, UserStatus status, String roleCode) {
        int maxPageSize = appProperties.getPagination().getMaxPageSize();
        Pageable pageable =
                PaginationUtils.createPageable(
                        page,
                        size,
                        sort,
                        ALLOWED_SORT_FIELDS,
                        "createdAt",
                        Sort.Direction.DESC,
                        maxPageSize);
        String normalizedSearch = PaginationUtils.normalizeSearchKeyword(search);
        Page<User> userPage =
                userRepository.findAll(
                        UserSpecifications.withFilters(normalizedSearch, status, roleCode),
                        pageable);
        return PageResponse.from(userPage, userMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        User user = getRequiredEntityById(id);
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public User getRequiredEntityById(Long id) {
        return userRepository
                .findWithRolesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String normalizedEmail = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateResourceException(
                    "A user with email '" + normalizedEmail + "' already exists");
        }

        Set<Role> assignedRoles =
                roleService.resolveRoles(request.getRoles(), SecurityConstants.ROLE_USER);
        UserStatus targetStatus =
                request.getStatus() != null ? request.getStatus() : UserStatus.ACTIVE;

        User user =
                User.builder()
                        .fullName(request.getFullName().trim())
                        .email(normalizedEmail)
                        .passwordHash(passwordEncoder.encode(request.getPassword()))
                        .status(targetStatus)
                        .roles(assignedRoles)
                        .build();

        User saved = userRepository.save(user);
        profiles.provision(saved);
        audit.record("USER_CREATE", "USER", saved.getId(), null, AuditSnapshots.user(saved), null);
        log.info(
                "Created user account id={} email={} status={}",
                saved.getId(),
                saved.getEmail(),
                saved.getStatus());
        return userMapper.toResponse(saved);
    }

    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request, String actorEmail) {
        roleService.lockAdminRole();
        User user = getRequiredEntityById(id);
        var before = AuditSnapshots.user(user);
        String normalizedEmail = normalizeEmail(request.getEmail());

        if (userRepository.existsByEmailIgnoreCaseAndIdNot(normalizedEmail, id)) {
            throw new DuplicateResourceException(
                    "Another user with email '" + normalizedEmail + "' already exists");
        }

        Set<Role> assignedRoles =
                roleService.resolveRoles(request.getRoles(), SecurityConstants.ROLE_USER);
        boolean retainsAdmin =
                assignedRoles.stream()
                        .anyMatch(r -> SecurityConstants.ROLE_ADMIN.equalsIgnoreCase(r.getCode()));

        if (actorEmail != null && user.getEmail().equalsIgnoreCase(actorEmail) && !retainsAdmin) {
            throw messages.error("cms.error.selfRole");
        }

        if (user.hasRole("ADMIN")
                && !retainsAdmin
                && user.isActive()
                && userRepository.countDistinctByRoles_CodeAndStatus("ADMIN", UserStatus.ACTIVE)
                        <= 1) {
            throw messages.error("cms.error.lastAdmin");
        }
        user.setFullName(request.getFullName().trim());
        user.setEmail(normalizedEmail);
        user.setRoles(assignedRoles);

        revocations.revoke(user);
        User updated = userRepository.save(user);
        profiles.sync(updated);
        audit.record("USER_UPDATE", "USER", id, before, AuditSnapshots.user(updated), null);
        log.info(
                "Updated user account id={} email={} by actor={}",
                updated.getId(),
                updated.getEmail(),
                actorEmail);
        return userMapper.toResponse(updated);
    }

    @Transactional
    public UserResponse updateUserStatus(
            Long id, UpdateUserStatusRequest request, String actorEmail) {
        return updateAccountStatus(
                id, request.getStatus(), null, "Account status update", actorEmail);
    }

    @Transactional
    public UserResponse updateAccountStatus(
            Long id, UserStatus status, Instant lockedUntil, String reason, String actorEmail) {
        roleService.lockAdminRole();
        User user = getRequiredEntityById(id);
        if (actorEmail != null
                && user.getEmail().equalsIgnoreCase(actorEmail)
                && status != UserStatus.ACTIVE) {
            throw new BusinessException(
                    "Administrators cannot deactivate or lock their own active account");
        }
        if (user.hasRole("ADMIN")
                && status != UserStatus.ACTIVE
                && user.isActive()
                && userRepository.countDistinctByRoles_CodeAndStatus("ADMIN", UserStatus.ACTIVE)
                        <= 1) {
            throw messages.error("cms.error.lastAdmin");
        }
        if (status == UserStatus.LOCKED
                && lockedUntil != null
                && !lockedUntil.isAfter(Instant.now())) {
            throw messages.error("cms.error.lockFuture");
        }
        var before = AuditSnapshots.user(user);
        user.setStatus(status);
        user.setLockedUntil(status == UserStatus.LOCKED ? lockedUntil : null);
        revocations.revoke(user);
        User updated = userRepository.save(user);
        audit.record(
                "USER_STATUS_UPDATE", "USER", id, before, AuditSnapshots.user(updated), reason);
        return userMapper.toResponse(updated);
    }

    @Transactional(readOnly = true)
    public long countAllUsers() {
        return userRepository.count();
    }

    @Transactional(readOnly = true)
    public long countActiveUsers() {
        return userRepository.countByStatus(UserStatus.ACTIVE);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
