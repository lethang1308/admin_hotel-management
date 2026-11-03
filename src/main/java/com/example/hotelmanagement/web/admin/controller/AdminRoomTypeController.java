package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.amenity.service.AmenityService;
import com.example.hotelmanagement.modules.roomtype.dto.RoomTypeForm;
import com.example.hotelmanagement.modules.roomtype.service.RoomTypeService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/admin/room-types")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('room.manage')")
public class AdminRoomTypeController {
    private final RoomTypeService types;
    private final AmenityService amenities;
    private final CmsMessages messages;

    @GetMapping
    public String list(Model m) {
        m.addAttribute("rows", types.list());
        return "admin/roomtypes/list";
    }

    @GetMapping("/create")
    public String create(Model m) {
        m.addAttribute("form", new RoomTypeForm());
        prepare(null, m);
        return "admin/roomtypes/form";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("form", types.form(id));
        prepare(id, m);
        return "admin/roomtypes/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") RoomTypeForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(null, f, e, m, flash);
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") RoomTypeForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(id, f, e, m, flash);
    }

    private String save(
            Long id, RoomTypeForm f, BindingResult e, Model m, RedirectAttributes flash) {
        if (!e.hasErrors())
            try {
                Long saved = types.save(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/room-types/" + saved + "/edit";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        prepare(id, m);
        return "admin/roomtypes/form";
    }

    private void prepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute("formAction", "/admin/room-types" + (id == null ? "" : "/" + id));
        m.addAttribute("amenities", amenities.list());
        if (id != null) m.addAttribute("images", types.images(id));
    }

    @PostMapping("/{id}/images")
    public String images(
            @PathVariable Long id,
            @RequestParam(defaultValue = "") String urls,
            @RequestParam(defaultValue = "") String altText,
            @RequestParam(required = false) List<MultipartFile> files,
            RedirectAttributes flash) {
        try {
            types.addImages(id, urls, altText, files);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/room-types/" + id + "/edit#gallery";
    }

    @PostMapping("/{id}/images/{imageId}/move")
    public String move(
            @PathVariable Long id,
            @PathVariable Long imageId,
            @RequestParam int position,
            RedirectAttributes flash) {
        try {
            types.moveImage(id, imageId, position);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/room-types/" + id + "/edit#gallery";
    }

    @PostMapping("/{id}/images/{imageId}/delete")
    public String deleteImage(
            @PathVariable Long id, @PathVariable Long imageId, RedirectAttributes flash) {
        try {
            types.deleteImage(id, imageId);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/room-types/" + id + "/edit#gallery";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            types.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/room-types";
    }
}
