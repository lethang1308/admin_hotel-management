package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.room.dto.RoomBlockForm;
import com.example.hotelmanagement.modules.room.service.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/room-blocks")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('room.block.manage')")
public class AdminRoomBlockController {
    private final RoomBlockService blocks;
    private final RoomQueryService query;
    private final HotelInventoryCoordinator guard;
    private final CmsMessages messages;

    @GetMapping
    public String list(@RequestParam(required = false) Long roomId, Model m) {
        var f = new RoomBlockForm();
        f.setRoomId(roomId);
        f.setFromDate(guard.today());
        f.setToDate(guard.today().plusDays(1));
        m.addAttribute("form", f);
        prepare(m);
        return "admin/rooms/blocks";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("form") RoomBlockForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        if (!e.hasErrors())
            try {
                blocks.create(f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/room-blocks";
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        prepare(m);
        return "admin/rooms/blocks";
    }

    private void prepare(Model m) {
        m.addAttribute("blocks", query.blocks(null));
        m.addAttribute("rooms", query.all(null, null, null, null, null));
        m.addAttribute("today", guard.today());
        m.addAttribute("horizon", guard.horizon());
    }

    @PostMapping("/{id}/release")
    public String release(
            @PathVariable Long id, @RequestParam String reason, RedirectAttributes flash) {
        try {
            blocks.release(id, reason);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/room-blocks";
    }
}
