package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.floor.dto.FloorForm;
import com.example.hotelmanagement.modules.floor.service.FloorService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/floors")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('room.manage')")
public class AdminFloorController {
    private final FloorService service;
    private final CmsMessages messages;

    @GetMapping
    public String list(Model m) {
        m.addAttribute("rows", service.list());
        return "admin/floors/list";
    }

    @GetMapping("/create")
    public String create(Model m) {
        m.addAttribute("form", new FloorForm());
        m.addAttribute("formAction", "/admin/floors");
        return "admin/floors/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("form", service.form(id));
        m.addAttribute("formAction", "/admin/floors/" + id);
        return "admin/floors/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") FloorForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(null, f, e, m, flash);
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") FloorForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(id, f, e, m, flash);
    }

    private String save(Long id, FloorForm f, BindingResult e, Model m, RedirectAttributes flash) {
        if (!e.hasErrors())
            try {
                service.save(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/floors";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        m.addAttribute("formAction", "/admin/floors" + (id == null ? "" : "/" + id));
        return "admin/floors/form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            service.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/floors";
    }
}
