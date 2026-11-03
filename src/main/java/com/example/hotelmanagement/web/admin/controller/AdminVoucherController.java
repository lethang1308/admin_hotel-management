package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.pricing.service.*;
import com.example.hotelmanagement.modules.voucher.dto.*;
import com.example.hotelmanagement.modules.voucher.entity.*;
import com.example.hotelmanagement.modules.voucher.service.VoucherService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/vouchers")
@PreAuthorize("hasAuthority('voucher.manage')")
public class AdminVoucherController {
    private final VoucherService vouchers;
    private final PricingCatalogueService catalogue;
    private final PricingSupport support;
    private final CmsMessages messages;

    @GetMapping
    public String list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) VoucherDiscountType discountType,
            @RequestParam(required = false) String validity,
            Model m) {
        m.addAttribute("rows", vouchers.list(search, active, discountType, validity));
        m.addAttribute("search", search);
        m.addAttribute("active", active);
        m.addAttribute("discountType", discountType);
        m.addAttribute("validity", validity);
        m.addAttribute("discountTypes", VoucherDiscountType.values());
        return "admin/vouchers/list";
    }

    @GetMapping("/create")
    public String create(Model m) {
        var f = new VoucherForm();
        f.setStartsAt(LocalDateTime.now(support.zone()).withNano(0));
        f.setExpiresAt(f.getStartsAt().plusMonths(1));
        m.addAttribute("form", f);
        prepare(null, m);
        return "admin/vouchers/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("form", vouchers.form(id));
        prepare(id, m);
        return "admin/vouchers/form";
    }

    @PostMapping({"", "/create"})
    public String create(
            @Valid @ModelAttribute("form") VoucherForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return save(null, f, errors, m, flash);
    }

    @PostMapping({"/{id}", "/{id}/edit"})
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") VoucherForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return save(id, f, errors, m, flash);
    }

    private String save(
            Long id, VoucherForm f, BindingResult errors, Model m, RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                Long result = vouchers.save(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/vouchers/" + result + "/edit";
            } catch (AppException e) {
                m.addAttribute("formError", e.getMessage());
            }
        prepare(id, m);
        return "admin/vouchers/form";
    }

    private void prepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute("formAction", "/admin/vouchers" + (id == null ? "" : "/" + id));
        m.addAttribute("types", catalogue.types());
        m.addAttribute("discountTypes", VoucherDiscountType.values());
        m.addAttribute("hotelZone", support.zone().toString());
        m.addAttribute("used", id != null && vouchers.get(id).used());
        if (id != null) m.addAttribute("target", vouchers.get(id));
    }

    @PostMapping("/{id}/{operation:toggle|delete}")
    public String action(
            @PathVariable Long id, @PathVariable String operation, RedirectAttributes flash) {
        try {
            if (operation.equals("toggle")) vouchers.toggle(id);
            else vouchers.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/vouchers";
    }

    @InitBinder
    public void trim(org.springframework.web.bind.WebDataBinder binder) {
        binder.registerCustomEditor(
                String.class,
                new org.springframework.beans.propertyeditors.StringTrimmerEditor(true));
    }
}
