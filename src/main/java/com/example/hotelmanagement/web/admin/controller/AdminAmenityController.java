package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.amenity.dto.AmenityForm;
import com.example.hotelmanagement.modules.amenity.service.AmenityService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/amenities")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('room.manage')")
public class AdminAmenityController {
    private final AmenityService service;
    private final CmsMessages messages;

    @GetMapping
    public String list(Model m) {
        m.addAttribute("rows", service.list());
        return "admin/amenities/list";
    }

    @GetMapping("/create")
    public String create(Model m) {
        m.addAttribute("form", new AmenityForm());
        m.addAttribute("formAction", "/admin/amenities");
        return "admin/amenities/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("form", service.form(id));
        m.addAttribute("formAction", "/admin/amenities/" + id);
        return "admin/amenities/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") AmenityForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(null, f, e, m, flash);
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") AmenityForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(id, f, e, m, flash);
    }

    private String save(
            Long id, AmenityForm f, BindingResult e, Model m, RedirectAttributes flash) {
        if (!e.hasErrors())
            try {
                service.save(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/amenities";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        m.addAttribute("formAction", "/admin/amenities" + (id == null ? "" : "/" + id));
        return "admin/amenities/form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/amenities";
    }
}
