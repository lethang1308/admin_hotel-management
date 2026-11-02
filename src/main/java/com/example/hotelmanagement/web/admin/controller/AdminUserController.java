package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserStatusRequest;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.security.authorization.SecurityConstants;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final RoleService roleService;

    @GetMapping
    public String listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String role,
            Model model
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        PageResponse<UserResponse> userPage = userService.getUsers(safePage, safeSize, sort, search, status, role);

        model.addAttribute("userPage", userPage);
        model.addAttribute("allRoles", roleService.findAll());
        model.addAttribute("statuses", UserStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedRole", role);
        model.addAttribute("sort", sort);
        return "admin/users/list";
    }

    @GetMapping("/create")
    public String createForm(Model model) {
        CreateUserRequest form = CreateUserRequest.builder()
                .status(UserStatus.ACTIVE)
                .roles(Set.of(SecurityConstants.ROLE_USER))
                .build();
        model.addAttribute("userForm", form);
        model.addAttribute("allRoles", roleService.findAll());
        model.addAttribute("statuses", UserStatus.values());
        return "admin/users/create";
    }

    @PostMapping
    public String createUser(
            @Valid @ModelAttribute("userForm") CreateUserRequest form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("allRoles", roleService.findAll());
            model.addAttribute("statuses", UserStatus.values());
            return "admin/users/create";
        }
        try {
            userService.createUser(form);
            redirectAttributes.addFlashAttribute("successMessage", "User account created successfully.");
            return "redirect:/admin/users";
        } catch (AppException ex) {
            model.addAttribute("formError", ex.getMessage());
            model.addAttribute("allRoles", roleService.findAll());
            model.addAttribute("statuses", UserStatus.values());
            return "admin/users/create";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        UserResponse user = userService.getUserById(id);
        UpdateUserRequest form = UpdateUserRequest.builder()
                .fullName(user.getFullName())
                .email(user.getEmail())
                .roles(new LinkedHashSet<>(user.getRoles()))
                .build();

        model.addAttribute("targetUser", user);
        model.addAttribute("userForm", form);
        model.addAttribute("allRoles", roleService.findAll());
        return "admin/users/edit";
    }

    @PostMapping("/{id}")
    public String updateUser(
            @PathVariable Long id,
            @Valid @ModelAttribute("userForm") UpdateUserRequest form,
            BindingResult bindingResult,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("targetUser", userService.getUserById(id));
            model.addAttribute("allRoles", roleService.findAll());
            return "admin/users/edit";
        }
        try {
            String actorEmail = authentication != null ? authentication.getName() : null;
            userService.updateUser(id, form, actorEmail);
            redirectAttributes.addFlashAttribute("successMessage", "User account updated successfully.");
            return "redirect:/admin/users";
        } catch (AppException ex) {
            model.addAttribute("targetUser", userService.getUserById(id));
            model.addAttribute("allRoles", roleService.findAll());
            model.addAttribute("formError", ex.getMessage());
            return "admin/users/edit";
        }
    }

    @PostMapping("/{id}/status")
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam UserStatus status,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String actorEmail = authentication != null ? authentication.getName() : null;
            userService.updateUserStatus(id, new UpdateUserStatusRequest(status), actorEmail);
            redirectAttributes.addFlashAttribute("successMessage", "User status updated to " + status + ".");
        } catch (AppException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users";
    }
}
