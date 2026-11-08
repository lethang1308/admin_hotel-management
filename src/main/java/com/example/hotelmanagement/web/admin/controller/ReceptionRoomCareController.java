package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.reception.GuestMessageService;
import com.example.hotelmanagement.modules.room.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequiredArgsConstructor
public class ReceptionRoomCareController {
    private final HousekeepingService cleaning;
    private final RoomMaintenanceService maintenance;
    private final OperationsStore db;
    private final OperationsWeb web;
    private final GuestRequestService requests;
    private final GuestMessageService messages;

    @GetMapping("/admin/receptionist/rooms")
    @PreAuthorize("hasAuthority('room.read')")
    public String home() {
        return "redirect:/admin/rooms/grid";
    }

    @GetMapping("/admin/receptionist/housekeeping")
    @PreAuthorize("hasAuthority('housekeeping.manage')")
    public String queue(@RequestParam(required = false) String status, Model m) {
        m.addAttribute("tasks", cleaning.queue(status));
        m.addAttribute("selectedStatus", status);
        return "admin/room-care/housekeeping";
    }

    @PostMapping("/admin/rooms/{id}/dirty")
    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('room.status.update')")
    public String dirty(
            @PathVariable long id,
            @RequestParam String notes,
            @RequestParam(defaultValue = "false") boolean priority,
            RedirectAttributes f) {
        web.mutate(() -> cleaning.request(id, notes, priority), f);
        return "redirect:/admin/receptionist/housekeeping";
    }

    @PostMapping("/admin/receptionist/housekeeping/{id}/{action}")
    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('room.status.update')")
    public String clean(
            @PathVariable long id,
            @PathVariable String action,
            @RequestParam String reason,
            RedirectAttributes f) {
        web.mutate(() -> cleaning.clean(id, action, reason), f);
        return "redirect:/admin/receptionist/housekeeping";
    }

    @GetMapping("/admin/maintenance")
    @PreAuthorize("hasAuthority('maintenance.manage')")
    public String maintenance(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long room,
            Model m) {
        m.addAttribute("requests", maintenance.list(status));
        m.addAttribute("selectedStatus", status);
        m.addAttribute("selectedRoom", room);
        m.addAttribute("today", db.today());
        m.addAttribute("requestKey", OperationsStore.code(""));
        m.addAttribute(
                "rooms",
                db.rows(
                        "SELECT id,room_number,status FROM rooms WHERE status<>'INACTIVE' ORDER BY"
                            + " room_number"));
        return "admin/room-care/maintenance";
    }

    @PostMapping("/admin/maintenance")
    @PreAuthorize("hasAuthority('maintenance.manage')")
    public String report(
            @RequestParam String key,
            @RequestParam long room,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam String priority,
            @RequestParam(defaultValue = "false") boolean block,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String reason,
            RedirectAttributes f) {
        web.mutate(
                () ->
                        maintenance.create(
                                new RoomMaintenanceService.Fault(
                                        key,
                                        room,
                                        title,
                                        description,
                                        priority,
                                        block,
                                        web.date(from),
                                        web.date(until),
                                        reason)),
                f);
        return "redirect:/admin/maintenance";
    }

    @PostMapping("/admin/maintenance/{id}/{action}")
    @PreAuthorize("hasAuthority('maintenance.manage')")
    public String resolve(
            @PathVariable long id,
            @PathVariable String action,
            @RequestParam String resolution,
            RedirectAttributes f) {
        web.mutate(() -> maintenance.update(id, action, resolution), f);
        return "redirect:/admin/maintenance";
    }

    @GetMapping("/admin/bookings/{id}/guest-requests/preview")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.update.any')")
    public ResponseEntity<GuestRequestService.Review> preview(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam int beds,
            @RequestParam int guests) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(requests.preview(id, line, beds, guests));
    }

    @PostMapping("/admin/bookings/{id}/guest-requests")
    @PreAuthorize("hasAuthority('booking.update.any')")
    public String preferences(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam int revision,
            @RequestParam(required = false) List<String> tags,
            @RequestParam int beds,
            @RequestParam int guests,
            @RequestParam(required = false) String lineNotes,
            @RequestParam(required = false) String special,
            @RequestParam(required = false) String internal,
            @RequestParam String reason,
            @RequestParam String token,
            RedirectAttributes f) {
        web.mutate(
                () ->
                        requests.save(
                                id,
                                new GuestRequestService.Form(
                                        line, revision, tags, beds, guests, lineNotes, special,
                                        internal, reason, token)),
                f);
        return "redirect:/admin/bookings/" + id;
    }

    @PostMapping("/admin/bookings/{id}/resend-email")
    @PreAuthorize("hasAuthority('notification.manage') and hasAuthority('booking.read.any')")
    public String resend(
            @PathVariable long id,
            @RequestParam String key,
            @RequestParam String recipient,
            RedirectAttributes f) {
        web.mutate(() -> messages.resend(id, key, recipient), f);
        return "redirect:/admin/bookings/" + id + "#guest-messages";
    }

    @ExceptionHandler(
            value = com.example.hotelmanagement.common.exception.AppException.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> error(
            com.example.hotelmanagement.common.exception.AppException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(Map.of("message", e.getMessage()));
    }
}
