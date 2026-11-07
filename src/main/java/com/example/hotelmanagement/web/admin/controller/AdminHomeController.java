package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.service.UserService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AdminHomeController {

    private final UserService userService;
    private final RoleService roleService;

    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String dashboard(
            Model model, org.springframework.security.core.Authentication authentication) {
        if (authentication.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
            return "redirect:/admin/receptionist";
        long totalUsers = userService.countAllUsers();
        long activeUsers = userService.countActiveUsers();
        int totalRoles = roleService.findAll().size();
        PageResponse<UserResponse> recentUsers =
                userService.getUsers(0, 5, "createdAt,desc", null, null, null);

        model.addAttribute("totalUsers", totalUsers);
        model.addAttribute("activeUsers", activeUsers);
        model.addAttribute("inactiveUsers", Math.max(0L, totalUsers - activeUsers));
        model.addAttribute("totalRoles", totalRoles);
        model.addAttribute("recentUsers", recentUsers.getItems());
        return "admin/dashboard";
    }
}
