package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.hotel.dto.HotelSettingForm;
import com.example.hotelmanagement.modules.hotel.service.HotelSettingService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/hotel")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('hotel.manage')")
public class AdminHotelController {
    private final HotelSettingService hotel;
    private final CmsMessages messages;

    @GetMapping
    public String get(Model m) {
        m.addAttribute("form", hotel.form());
        prepare(m);
        return "admin/hotel/settings";
    }

    @PostMapping
    public String save(
            @Valid @ModelAttribute("form") HotelSettingForm f,
            BindingResult errors,
            @RequestParam(required = false) MultipartFile logo,
            Model m,
            RedirectAttributes flash) {
        if (!errors.hasErrors())
            try {
                hotel.update(f, logo);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/hotel";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        prepare(m);
        return "admin/hotel/settings";
    }

    private void prepare(Model m) {
        m.addAttribute("hotel", hotel.get());
        m.addAttribute("depositPolicies", hotel.deposits());
        m.addAttribute("cancellationPolicies", hotel.cancellations());
    }
}
