package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.auth.dto.*;
import com.example.hotelmanagement.modules.employee.dto.EmployeeForm;
import com.example.hotelmanagement.modules.employee.service.EmployeeService;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.service.AccountPasswordService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;

@Controller
@RequestMapping("/admin/employees")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('account.manage')")
public class AdminEmployeeController {
    private final EmployeeService employees;
    private final AccountPasswordService passwords;
    private final CmsMessages messages;

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String position,
            Model m) {
        m.addAttribute("pageData", employees.search(page, size, search, status, position));
        m.addAttribute("search", search);
        m.addAttribute("selectedStatus", status);
        m.addAttribute("position", position);
        m.addAttribute("statuses", UserStatus.values());
        return "admin/employees/list";
    }

    @GetMapping("/create")
    public String create(Model m) {
        m.addAttribute("employeeForm", new EmployeeForm());
        return "admin/employees/create";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("employeeForm") EmployeeForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (errors.hasErrors()) return "admin/employees/create";
        try {
            Long id = employees.create(f);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
            return "redirect:/admin/employees/" + id + "/edit";
        } catch (AppException ex) {
            m.addAttribute("formError", ex.getMessage());
            return "admin/employees/create";
        }
    }

    @GetMapping({"/{id}", "/{id}/edit"})
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("employeeForm", employees.form(id));
        prepare(id, m);
        return "admin/employees/edit";
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("employeeForm") EmployeeForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                employees.update(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/employees/" + id + "/edit";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        prepare(id, m);
        return "admin/employees/edit";
    }

    @PostMapping("/{id}/status")
    public String status(
            @PathVariable Long id,
            @Valid @ModelAttribute("statusForm") AccountStatusForm f,
            BindingResult errors,
            Authentication auth,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                employees.status(
                        id,
                        f.getStatus(),
                        f.getLockedUntil() == null
                                ? null
                                : f.getLockedUntil()
                                        .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                                        .toInstant(),
                        f.getReason(),
                        auth.getName());
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/employees/" + id + "/edit";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        m.addAttribute("employeeForm", employees.form(id));
        prepare(id, m);
        return "admin/employees/edit";
    }

    @PostMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('account.manage')")
    public String password(
            @PathVariable Long id,
            @Valid @ModelAttribute("passwordForm") CmsPasswordForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                passwords.resetEmployee(id, f.getNewPassword(), f.getReason());
                flash.addFlashAttribute("successMessage", messages.text("cms.passwordResetDone"));
                return "redirect:/admin/employees/" + id + "/edit";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        m.addAttribute("employeeForm", employees.form(id));
        prepare(id, m);
        return "admin/employees/edit";
    }

    private void prepare(Long id, Model m) {
        var employee = employees.get(id);
        m.addAttribute("target", employee);
        m.addAttribute("statuses", UserStatus.values());
        if (!m.containsAttribute("statusForm")) {
            var f = new AccountStatusForm();
            f.setStatus(employee.status());
            if (employee.lockedUntil() != null)
                f.setLockedUntil(
                        java.time.LocalDateTime.ofInstant(
                                employee.lockedUntil(), ZoneId.of("Asia/Ho_Chi_Minh")));
            m.addAttribute("statusForm", f);
        }
        if (!m.containsAttribute("passwordForm"))
            m.addAttribute("passwordForm", new CmsPasswordForm());
    }
}
