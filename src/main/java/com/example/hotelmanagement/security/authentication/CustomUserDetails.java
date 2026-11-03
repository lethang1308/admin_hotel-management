package com.example.hotelmanagement.security.authentication;

import com.example.hotelmanagement.modules.user.entity.*;

import lombok.Getter;

import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.*;

@Getter
public class CustomUserDetails implements UserDetails {
    private final Long id;
    private final String fullName, email, password;
    private final UserStatus status;
    private final Instant lockedUntil;
    private final long securityVersion;
    private final Set<String> roleCodes;
    private final List<GrantedAuthority> authorities;

    public CustomUserDetails(User user) {
        id = user.getId();
        fullName = user.getFullName();
        email = user.getEmail();
        password = user.getPasswordHash();
        status = user.getStatus();
        lockedUntil = user.getLockedUntil();
        securityVersion = user.getSecurityVersion() == null ? 0 : user.getSecurityVersion();
        Set<String> roles = new LinkedHashSet<>(), granted = new TreeSet<>();
        user.getRoles()
                .forEach(
                        r -> {
                            roles.add(r.getCode().toUpperCase(Locale.ROOT));
                            granted.add("ROLE_" + r.getCode().toUpperCase(Locale.ROOT));
                            if (r.getPermissions() != null)
                                r.getPermissions().forEach(p -> granted.add(p.getCode()));
                        });
        roleCodes = Set.copyOf(roles);
        authorities =
                granted.stream()
                        .map(x -> (GrantedAuthority) new SimpleGrantedAuthority(x))
                        .toList();
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return (status != UserStatus.LOCKED
                        || (lockedUntil != null && !lockedUntil.isAfter(Instant.now())))
                && (lockedUntil == null || !lockedUntil.isAfter(Instant.now()));
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE
                || (status == UserStatus.LOCKED
                        && lockedUntil != null
                        && !lockedUntil.isAfter(Instant.now()));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CustomUserDetails d && Objects.equals(id, d.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
