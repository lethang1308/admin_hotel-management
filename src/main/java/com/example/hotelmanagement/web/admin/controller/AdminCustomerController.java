package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.customer.dto.CustomerForm;
import com.example.hotelmanagement.modules.customer.service.CustomerService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/customers")
@RequiredArgsConstructor
public class AdminCustomerController {
    private final CustomerService customers;
    private final CmsMessages messages;

    @GetMapping
    @PreAuthorize("hasAuthority('customer.read')")
    public String list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String nationality,
            Model m) {
        m.addAttribute("pageData", customers.search(page, size, search, nationality));
        m.addAttribute("search", search);
        m.addAttribute("nationality", nationality);
        return "admin/customers/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer.read')")
    public String detail(@PathVariable Long id, Model m) {
        m.addAttribute("target", customers.get(id));
        return "admin/customers/detail";
    }

    @GetMapping("/create")
    @PreAuthorize("hasAuthority('customer.update')")
    public String create(Model m) {
        m.addAttribute("customerForm", new CustomerForm());
        return "admin/customers/create";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer.update')")
    public String create(
            @Valid @ModelAttribute("customerForm") CustomerForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                Long id = customers.create(f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/customers/" + id;
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        return "admin/customers/create";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('customer.update')")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("customerForm", customers.form(id));
        m.addAttribute("targetId", id);
        return "admin/customers/edit";
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('customer.update')")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("customerForm") CustomerForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                customers.update(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/customers/" + id;
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        m.addAttribute("targetId", id);
        return "admin/customers/edit";
    }
}
