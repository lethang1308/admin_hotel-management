package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.modules.role.service.RoleService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize(
        "hasRole('ADMIN') and hasAuthority('role.manage')")
public class AdminRoleController {

    private final RoleService roleService;
    private final com.example.hotelmanagement.common.util.CmsMessages messages;

    @GetMapping
    public String listRoles(Model model) {
        model.addAttribute("roles", roleService.matrix());
        model.addAttribute("permissions", roleService.allPermissions());
        return "admin/roles/matrix";
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/permissions")
    public String update(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam(
                            name = "permissions",
                            required = false)
                    java.util.Set<Long> ids,
            org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        try {
            roleService.updatePermissions(id, ids == null ? java.util.Set.of() : ids);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (com.example.hotelmanagement.common.exception.AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/roles";
    }
}
