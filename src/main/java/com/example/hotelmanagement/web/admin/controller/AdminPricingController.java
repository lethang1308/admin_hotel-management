package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.pricing.dto.*;
import com.example.hotelmanagement.modules.pricing.entity.*;
import com.example.hotelmanagement.modules.pricing.service.*;

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
@RequestMapping("/admin/pricing")
public class AdminPricingController {
    private final RateRuleService rules;
    private final RoomPricingEngineService engine;
    private final PricingCatalogueService catalogue;
    private final PricingSupport support;
    private final HotelInventoryCoordinator guard;
    private final CmsMessages messages;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('pricing.manage','room.read')")
    public String list(
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false)
                    @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    LocalDate until,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RateRuleKind kind,
            @RequestParam(required = false) Boolean active,
            Model m) {
        if (from == null) from = guard.today();
        if (until == null) until = from.plusDays(14);
        m.addAttribute("from", from);
        m.addAttribute("until", until);
        m.addAttribute("search", search);
        m.addAttribute("kind", kind);
        m.addAttribute("active", active);
        m.addAttribute("rows", rules.list(search, kind, active));
        m.addAttribute("types", catalogue.types());
        m.addAttribute("kinds", RateRuleKind.values());
        try {
            m.addAttribute("calendar", engine.calendar(from, until));
        } catch (AppException e) {
            m.addAttribute("calendarError", e.getMessage());
        }
        return "admin/pricing/index";
    }

    @PostMapping("/base-prices")
    @PreAuthorize("hasAnyAuthority('pricing.manage','room.manage')")
    public String basePrice(
            @Valid @ModelAttribute BasePriceForm f,
            BindingResult errors,
            RedirectAttributes flash) {
        if (errors.hasErrors())
            flash.addFlashAttribute("errorMessage", messages.text("price.error.form"));
        else
            try {
                rules.basePrice(f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
            } catch (AppException e) {
                flash.addFlashAttribute("errorMessage", e.getMessage());
            }
        return "redirect:/admin/pricing";
    }

    @GetMapping("/rules/create")
    @PreAuthorize("hasAuthority('pricing.manage')")
    public String create(Model m) {
        var f = new RateRuleForm();
        f.setStartDate(guard.today());
        f.setEndDate(guard.today().plusMonths(3));
        m.addAttribute("form", f);
        prepare(null, m);
        return "admin/pricing/rule-form";
    }

    @GetMapping("/rules/{id}/edit")
    @PreAuthorize("hasAuthority('pricing.manage')")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("form", rules.form(id));
        prepare(id, m);
        return "admin/pricing/rule-form";
    }

    @PostMapping({"/rules", "/rules/create"})
    @PreAuthorize("hasAuthority('pricing.manage')")
    public String create(
            @Valid @ModelAttribute("form") RateRuleForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return save(null, f, errors, m, flash);
    }

    @PostMapping({"/rules/{id}", "/rules/{id}/edit"})
    @PreAuthorize("hasAuthority('pricing.manage')")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") RateRuleForm f,
            BindingResult errors,
            Model m,
            RedirectAttributes flash) {
        return save(id, f, errors, m, flash);
    }

    private String save(
            Long id, RateRuleForm f, BindingResult errors, Model m, RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                Long result = rules.save(id, f);
                flash.addFlashAttribute(
                        "successMessage",
                        messages.text(
                                id != null && !id.equals(result)
                                        ? "price.versionCreated"
                                        : "cms.saved"));
                return "redirect:/admin/pricing/rules/" + result + "/edit";
            } catch (AppException e) {
                m.addAttribute("formError", e.getMessage());
            }
        prepare(id, m);
        return "admin/pricing/rule-form";
    }

    private void prepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute("formAction", "/admin/pricing/rules" + (id == null ? "" : "/" + id));
        m.addAttribute("types", catalogue.types());
        m.addAttribute("kinds", RateRuleKind.values());
        m.addAttribute("valueTypes", RateValueType.values());
        if (id != null) m.addAttribute("target", rules.get(id));
    }

    @PostMapping("/rules/{id}/{operation:toggle|delete}")
    @PreAuthorize("hasAuthority('pricing.manage')")
    public String action(
            @PathVariable Long id, @PathVariable String operation, RedirectAttributes flash) {
        try {
            if (operation.equals("toggle")) rules.toggle(id);
            else rules.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/pricing";
    }

    @GetMapping("/simulator")
    @PreAuthorize("hasAnyAuthority('pricing.manage','booking.create')")
    public String simulator(Model m) {
        var f = new PricingSimulationForm();
        f.getRooms().get(0).setCheckIn(guard.today().plusDays(1));
        f.getRooms().get(0).setCheckOut(guard.today().plusDays(3));
        m.addAttribute("form", f);
        simulation(m);
        return "admin/pricing/simulator";
    }

    @PostMapping("/simulator")
    @PreAuthorize("hasAnyAuthority('pricing.manage','booking.create')")
    public String simulator(
            @Valid @ModelAttribute("form") PricingSimulationForm f, BindingResult errors, Model m) {
        if (!errors.hasErrors())
            try {
                var at =
                        f.getEvaluationTime() == null
                                ? Instant.now()
                                : f.getEvaluationTime().atZone(support.zone()).toInstant();
                m.addAttribute(
                        "quote",
                        engine.quote(
                                new PricingModels.Request(
                                        f.getRooms().stream()
                                                .map(
                                                        r ->
                                                                new PricingModels.Stay(
                                                                        r.getRoomTypeId(),
                                                                        r.getCheckIn(),
                                                                        r.getCheckOut(),
                                                                        r.getQuantity()))
                                                .toList(),
                                        f.getVoucherCode(),
                                        f.getCustomerId(),
                                        at,
                                        f.getCancellationTime() == null
                                                ? null
                                                : f.getCancellationTime()
                                                        .atZone(support.zone())
                                                        .toInstant())));
            } catch (AppException e) {
                m.addAttribute("formError", e.getMessage());
            }
        simulation(m);
        return "admin/pricing/simulator";
    }

    private void simulation(Model m) {
        m.addAttribute("types", catalogue.types().stream().filter(t -> t.active()).toList());
        m.addAttribute("hotelZone", support.zone().toString());
    }

    @InitBinder
    public void limitBinding(org.springframework.web.bind.WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(30);
        binder.registerCustomEditor(
                String.class,
                new org.springframework.beans.propertyeditors.StringTrimmerEditor(true));
    }
}
