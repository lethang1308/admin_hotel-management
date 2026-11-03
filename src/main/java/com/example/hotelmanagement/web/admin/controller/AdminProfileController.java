package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.auth.dto.CmsPasswordForm;
import com.example.hotelmanagement.modules.user.service.AccountPasswordService;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/profile")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('profile.manage.own')")
public class AdminProfileController {
    private final AccountPasswordService passwords;

    @GetMapping("/password")
    public String form(Model m) {
        m.addAttribute("passwordForm", new CmsPasswordForm());
        return "admin/profile/password";
    }

    @PostMapping("/password")
    public String change(
            @Valid @ModelAttribute("passwordForm") CmsPasswordForm f,
            BindingResult errors,
            Authentication auth,
            HttpServletRequest request,
            HttpServletResponse response,
            Model m) {
        if (!errors.hasErrors())
            try {
                passwords.changeOwn(
                        ((CustomUserDetails) auth.getPrincipal()).getId(),
                        f.getCurrentPassword(),
                        f.getNewPassword());
                new SecurityContextLogoutHandler().logout(request, response, auth);
                return "redirect:/admin/login?passwordChanged=true";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        return "admin/profile/password";
    }
}
