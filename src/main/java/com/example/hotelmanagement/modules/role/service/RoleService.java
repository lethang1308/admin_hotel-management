package com.example.hotelmanagement.modules.role.service;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.repository.RoleRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;

    @Transactional(readOnly = true)
    public List<Role> findAll() {
        return roleRepository.findAll(Sort.by(Sort.Direction.ASC, "code"));
    }

    @Transactional(readOnly = true)
    public Role getRequiredByCode(String code) {
        String normalized = normalizeCode(code);
        return roleRepository.findByCode(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Role", normalized));
    }

    @Transactional(readOnly = true)
    public Set<Role> resolveRoles(Collection<String> roleCodes, String defaultRoleCode) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return Set.of(getRequiredByCode(defaultRoleCode));
        }

        Set<String> normalizedCodes = roleCodes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(this::normalizeCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalizedCodes.isEmpty()) {
            return Set.of(getRequiredByCode(defaultRoleCode));
        }

        List<Role> foundRoles = roleRepository.findByCodeIn(normalizedCodes);
        if (foundRoles.size() != normalizedCodes.size()) {
            Set<String> foundCodes = foundRoles.stream().map(Role::getCode).collect(Collectors.toSet());
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
