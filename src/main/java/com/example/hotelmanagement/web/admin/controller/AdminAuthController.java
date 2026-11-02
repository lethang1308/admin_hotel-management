package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.security.authorization.SecurityConstants;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminAuthController {

    @GetMapping("/admin/login")
    public String loginPage(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> ("ROLE_" + SecurityConstants.ROLE_ADMIN).equals(a.getAuthority()));
            if (isAdmin) {
                return "redirect:/admin";
            }
        }
        return "admin/auth/login";
    }
}
