package com.example.hotelmanagement.common.util;

import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CmsActor {
    private CmsActor() {}

    public static Long id() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof CustomUserDetails d ? d.getId() : null;
    }
}
