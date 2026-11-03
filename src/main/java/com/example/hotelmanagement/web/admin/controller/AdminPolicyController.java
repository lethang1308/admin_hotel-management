package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.policy.dto.*;
import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.policy.service.*;
import com.example.hotelmanagement.modules.pricing.service.PricingCatalogueService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/policies")
@PreAuthorize("hasAuthority('policy.manage')")
public class AdminPolicyController {
    private final DepositPolicyService deposits;
    private final CancellationPolicyService cancellations;
    private final SurchargePolicyService surcharges;
    private final PricingCatalogueService catalogue;
    private final CmsMessages messages;

    @GetMapping
    public String list(Model m) {
        m.addAttribute("deposits", deposits.list());
        m.addAttribute("cancellations", cancellations.list());
        m.addAttribute("surcharges", surcharges.list());
        return "admin/policies/index";
    }

    @GetMapping("/deposits/create")
    public String depositCreate(Model m) {
        m.addAttribute("form", new DepositPolicyForm());
        depositPrepare(null, m);
        return "admin/policies/deposits-form";
    }

    @GetMapping("/deposits/{id}/edit")
    public String depositEdit(@PathVariable Long id, Model m) {
        m.addAttribute("form", deposits.form(id));
        depositPrepare(id, m);
        return "admin/policies/deposits-form";
    }

    @PostMapping({"/deposits", "/deposits/create"})
    public String depositCreate(
            @Valid @ModelAttribute("form") DepositPolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return depositSave(null, f, errors, m, flash);
    }

    @PostMapping({"/deposits/{id}", "/deposits/{id}/edit"})
    public String depositUpdate(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") DepositPolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return depositSave(id, f, errors, m, flash);
    }

    private String depositSave(
            Long id, DepositPolicyForm f, BindingResult errors, Model m, RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                Long result = deposits.save(id, f);
                flash.addFlashAttribute(
                        "successMessage",
                        messages.text(
                                id != null && !id.equals(result)
                                        ? "price.versionCreated"
                                        : "cms.saved"));
                return "redirect:/admin/policies/deposits/" + result + "/edit";
            } catch (AppException e) {
                m.addAttribute("formError", e.getMessage());
            }
        depositPrepare(id, m);
        return "admin/policies/deposits-form";
    }

    private void depositPrepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute("formAction", "/admin/policies/deposits" + (id == null ? "" : "/" + id));
        if (id != null) m.addAttribute("target", deposits.get(id));
        m.addAttribute("amountTypes", DepositAmountType.values());
    }

    @PostMapping("/deposits/{id}/{operation:default|toggle|delete}")
    public String depositAction(
            @PathVariable Long id, @PathVariable String operation, RedirectAttributes flash) {
        try {
            if (operation.equals("default")) deposits.makeDefault(id);
            else if (operation.equals("toggle")) deposits.toggle(id);
            else deposits.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/policies";
    }

    @GetMapping("/cancellations/create")
    public String cancellationCreate(Model m) {
        m.addAttribute("form", new CancellationPolicyForm());
        cancellationPrepare(null, m);
        return "admin/policies/cancellations-form";
    }

    @GetMapping("/cancellations/{id}/edit")
    public String cancellationEdit(@PathVariable Long id, Model m) {
        m.addAttribute("form", cancellations.form(id));
        cancellationPrepare(id, m);
        return "admin/policies/cancellations-form";
    }

    @PostMapping({"/cancellations", "/cancellations/create"})
    public String cancellationCreate(
            @Valid @ModelAttribute("form") CancellationPolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return cancellationSave(null, f, errors, m, flash);
    }

    @PostMapping({"/cancellations/{id}", "/cancellations/{id}/edit"})
    public String cancellationUpdate(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") CancellationPolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return cancellationSave(id, f, errors, m, flash);
    }

    private String cancellationSave(
            Long id,
            CancellationPolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                Long result = cancellations.save(id, f);
                flash.addFlashAttribute(
                        "successMessage",
                        messages.text(
                                id != null && !id.equals(result)
                                        ? "price.versionCreated"
                                        : "cms.saved"));
                return "redirect:/admin/policies/cancellations/" + result + "/edit";
            } catch (AppException e) {
                m.addAttribute("formError", e.getMessage());
            }
        cancellationPrepare(id, m);
        return "admin/policies/cancellations-form";
    }

    private void cancellationPrepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute(
                "formAction", "/admin/policies/cancellations" + (id == null ? "" : "/" + id));
        if (id != null) m.addAttribute("target", cancellations.get(id));
        m.addAttribute("feeTypes", FeeType.values());
    }

    @PostMapping("/cancellations/{id}/{operation:default|toggle|delete}")
    public String cancellationAction(
            @PathVariable Long id, @PathVariable String operation, RedirectAttributes flash) {
        try {
            if (operation.equals("default")) cancellations.makeDefault(id);
            else if (operation.equals("toggle")) cancellations.toggle(id);
            else cancellations.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/policies";
    }

    @GetMapping("/surcharges/create")
    public String surchargeCreate(Model m) {
        m.addAttribute("form", new SurchargePolicyForm());
        surchargePrepare(null, m);
        return "admin/policies/surcharges-form";
    }

    @GetMapping("/surcharges/{id}/edit")
    public String surchargeEdit(@PathVariable Long id, Model m) {
        m.addAttribute("form", surcharges.form(id));
        surchargePrepare(id, m);
        return "admin/policies/surcharges-form";
    }

    @PostMapping({"/surcharges", "/surcharges/create"})
    public String surchargeCreate(
            @Valid @ModelAttribute("form") SurchargePolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return surchargeSave(null, f, errors, m, flash);
    }

    @PostMapping({"/surcharges/{id}", "/surcharges/{id}/edit"})
    public String surchargeUpdate(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") SurchargePolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return surchargeSave(id, f, errors, m, flash);
    }

    private String surchargeSave(
            Long id,
            SurchargePolicyForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                Long result = surcharges.save(id, f);
                flash.addFlashAttribute(
                        "successMessage",
                        messages.text(
                                id != null && !id.equals(result)
                                        ? "price.versionCreated"
                                        : "cms.saved"));
                return "redirect:/admin/policies/surcharges/" + result + "/edit";
            } catch (AppException e) {
                m.addAttribute("formError", e.getMessage());
            }
        surchargePrepare(id, m);
        return "admin/policies/surcharges-form";
    }

    private void surchargePrepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute("formAction", "/admin/policies/surcharges" + (id == null ? "" : "/" + id));
        if (id != null) m.addAttribute("target", surcharges.get(id));
        m.addAttribute("kinds", SurchargeKind.values());
        m.addAttribute("amountTypes", SurchargeAmountType.values());
        m.addAttribute("types", catalogue.types());
    }

    @PostMapping("/surcharges/{id}/{operation:toggle|delete}")
    public String surchargeAction(
            @PathVariable Long id, @PathVariable String operation, RedirectAttributes flash) {
        try {
            if (operation.equals("toggle")) surcharges.toggle(id);
            else surcharges.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/policies";
    }

    @InitBinder
    public void limitBinding(org.springframework.web.bind.WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(30);
        binder.registerCustomEditor(
                String.class,
                new org.springframework.beans.propertyeditors.StringTrimmerEditor(true));
    }
}
