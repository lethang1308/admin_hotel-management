package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.floor.service.FloorService;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.room.dto.*;
import com.example.hotelmanagement.modules.room.entity.*;
import com.example.hotelmanagement.modules.room.service.*;
import com.example.hotelmanagement.modules.roomtype.service.RoomTypeService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/rooms")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('room.read')")
public class AdminRoomController {
    private final RoomService rooms;
    private final RoomQueryService query;
    private final FloorService floors;
    private final RoomTypeService types;
    private final HotelInventoryCoordinator guard;
    private final CmsMessages messages;
    private final com.example.hotelmanagement.modules.reception.RoomCareQuery care;

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long floorId,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) RoomStatus status,
            @RequestParam(required = false) String search,
            Model m) {
        m.addAttribute("pageData", query.search(page, size, floorId, area, typeId, status, search));
        filters(floorId, area, typeId, status, m);
        m.addAttribute("search", search);
        return "admin/rooms/list";
    }

    @GetMapping("/grid")
    public String grid(
            @RequestParam(required = false) Long floorId,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) RoomStatus status,
            Model m) {
        m.addAttribute("grid", query.grid(floorId, area, typeId, status));
        m.addAttribute(
                "careFaultKey",
                com.example.hotelmanagement.modules.booking.OperationsStore.code(""));
        var auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        m.addAttribute(
                "occupants",
                auth.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("booking.read.any"))
                        ? care.occupied()
                        : java.util.Map.of());
        filters(floorId, area, typeId, status, m);
        return "admin/rooms/grid";
    }

    private void filters(Long floorId, String area, Long typeId, RoomStatus status, Model m) {
        m.addAttribute("floors", floors.list());
        m.addAttribute("types", types.list());
        m.addAttribute("areas", query.areas());
        m.addAttribute("statuses", RoomStatus.values());
        m.addAttribute("floorId", floorId);
        m.addAttribute("area", area);
        m.addAttribute("typeId", typeId);
        m.addAttribute("selectedStatus", status);
        m.addAttribute("today", guard.today());
    }

    @GetMapping("/create")
    @PreAuthorize("hasAuthority('room.manage')")
    public String create(Model m) {
        var f = new RoomForm();
        f.setSellFrom(guard.today());
        m.addAttribute("form", f);
        prepare(null, m);
        return "admin/rooms/form";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('room.manage')")
    public String edit(@PathVariable Long id, Model m) {
        m.addAttribute("form", rooms.form(id));
        prepare(id, m);
        return "admin/rooms/form";
    }

    private void prepare(Long id, Model m) {
        m.addAttribute("targetId", id);
        m.addAttribute("formAction", "/admin/rooms" + (id == null ? "" : "/" + id));
        m.addAttribute("floors", floors.list());
        m.addAttribute("types", types.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('room.manage')")
    public String create(
            @Valid @ModelAttribute("form") RoomForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(null, f, e, m, flash);
    }

    @PostMapping("/{id}")
    @PreAuthorize("hasAuthority('room.manage')")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("form") RoomForm f,
            BindingResult e,
            Model m,
            RedirectAttributes flash) {
        return save(id, f, e, m, flash);
    }

    private String save(Long id, RoomForm f, BindingResult e, Model m, RedirectAttributes flash) {
        if (!e.hasErrors())
            try {
                Long saved = rooms.save(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                return "redirect:/admin/rooms/" + saved;
            } catch (AppException ex) {
                m.addAttribute("formError", ex.getMessage());
            }
        prepare(id, m);
        return "admin/rooms/form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model m) {
        m.addAttribute("room", query.get(id));
        m.addAttribute("stays", query.stays(id));
        m.addAttribute("history", query.history(id));
        m.addAttribute("blocks", query.blocks(id));
        m.addAttribute("statuses", RoomStatus.values());
        m.addAttribute("today", guard.today());
        m.addAttribute(
                "type",
                types.list().stream()
                        .filter(t -> t.id().equals(query.get(id).roomTypeId()))
                        .findFirst()
                        .orElseThrow());
        return "admin/rooms/detail";
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('room.status.update')")
    public String status(
            @PathVariable Long id,
            @Valid @ModelAttribute RoomStatusChangeForm f,
            BindingResult e,
            @RequestParam(defaultValue = "detail") String returnView,
            RedirectAttributes flash) {
        if (e.hasErrors())
            flash.addFlashAttribute("errorMessage", messages.text("hotel.error.form"));
        else
            try {
                rooms.changeStatus(id, f);
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
            } catch (AppException ex) {
                flash.addFlashAttribute("errorMessage", ex.getMessage());
            }
        return "grid".equals(returnView)
                ? "redirect:/admin/rooms/grid"
                : "redirect:/admin/rooms/" + id;
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasAuthority('room.manage')")
    public String retire(
            @PathVariable Long id, @RequestParam String reason, RedirectAttributes flash) {
        try {
            rooms.retire(id, reason);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/rooms/" + id;
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('room.manage')")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        try {
            rooms.delete(id);
            flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
            return "redirect:/admin/rooms";
        } catch (AppException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/rooms/" + id;
        }
    }
}
