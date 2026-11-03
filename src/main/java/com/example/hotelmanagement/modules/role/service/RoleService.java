package com.example.hotelmanagement.modules.role.service;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.repository.RoleRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final com.example.hotelmanagement.modules.role.repository.PermissionRepository
            permissions;
    private final com.example.hotelmanagement.modules.audit.service.AuditLogService audit;
    private final com.example.hotelmanagement.common.util.CmsMessages messages;

    public void lockAdminRole() {
        roleRepository.lockAdminRole().orElseThrow();
    }

    @Transactional(readOnly = true)
    public java.util.List<com.example.hotelmanagement.modules.role.entity.Permission>
            allPermissions() {
        return permissions.findAll(Sort.by("code"));
    }

    public record MatrixRow(Long id, String code, String name, java.util.Set<Long> permissionIds) {}

    @Transactional(readOnly = true)
    public java.util.List<MatrixRow> matrix() {
        return findAll().stream()
                .map(
                        r ->
                                new MatrixRow(
                                        r.getId(),
                                        r.getCode(),
                                        r.getName(),
                                        r.getPermissions().stream()
                                                .map(p -> p.getId())
                                                .collect(java.util.stream.Collectors.toSet())))
                .toList();
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasRole('ADMIN') and hasAuthority('role.manage')")
    public void updatePermissions(Long id, java.util.Set<Long> ids) {
        lockAdminRole();
        Role role =
                roleRepository
                        .findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Role", id));
        var selected = permissions.findAllById(ids);
        if (selected.size() != ids.size()) throw messages.error("cms.error.permission");
        if ("ADMIN".equals(role.getCode()) && ids.size() != permissions.count())
            throw messages.error("cms.error.adminPermissions");
        var before =
                java.util.Map.of(
                        "permissions",
                        role.getPermissions().stream().map(p -> p.getCode()).sorted().toList());
        role.setPermissions(new java.util.LinkedHashSet<>(selected));
        audit.record(
                "ROLE_PERMISSIONS_UPDATE",
                "ROLE",
                id,
                before,
                java.util.Map.of(
                        "permissions", selected.stream().map(p -> p.getCode()).sorted().toList()),
                null);
    }

    @Transactional(readOnly = true)
    public List<Role> findAll() {
        return roleRepository.findAll(Sort.by(Sort.Direction.ASC, "code"));
    }

    @Transactional(readOnly = true)
    public Role getRequiredByCode(String code) {
        String normalized = normalizeCode(code);
        return roleRepository
                .findByCode(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Role", normalized));
    }

    @Transactional(readOnly = true)
    public Set<Role> resolveRoles(Collection<String> roleCodes, String defaultRoleCode) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Set.of(getRequiredByCode(defaultRoleCode));
        }

        Set<String> normalizedCodes =
                roleCodes.stream()
                        .filter(c -> c != null && !c.isBlank())
                        .map(this::normalizeCode)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalizedCodes.isEmpty()) {
            return Set.of(getRequiredByCode(defaultRoleCode));
        }

        List<Role> foundRoles = roleRepository.findByCodeIn(normalizedCodes);
        if (foundRoles.size() != normalizedCodes.size()) {
            Set<String> foundCodes =
                    foundRoles.stream().map(Role::getCode).collect(Collectors.toSet());
            Set<String> missing = new LinkedHashSet<>(normalizedCodes);
            missing.removeAll(foundCodes);
            throw new BusinessException("Unknown role code(s): " + missing);
        }

        return new LinkedHashSet<>(foundRoles);
    }

    private String normalizeCode(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring(5);
        }
        return normalized;
    }
}
