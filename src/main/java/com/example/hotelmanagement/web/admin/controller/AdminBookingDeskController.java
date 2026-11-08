package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.booking.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;

@Controller
@RequestMapping("/admin/bookings")
@RequiredArgsConstructor
public class AdminBookingDeskController {
    private final BookingAvailabilityService availability;
    private final BookingDeskOperations desk;
    private final BookingChangeService changes;
    private final StayService stays;
    private final BookingService bookings;
    private final OperationsStore db;
    private final OperationsWeb web;

    @GetMapping("/availability")
    @PreAuthorize("hasAnyAuthority('room.read','booking.create')")
    @ResponseBody
    public ResponseEntity<BookingAvailabilityService.Result> availability(
            @RequestParam String from,
            @RequestParam String until,
            @RequestParam(defaultValue = "1") int adults,
            @RequestParam(defaultValue = "0") int children,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(required = false) Long typeId,
            @RequestParam(defaultValue = "false") boolean readyNow) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        availability.check(
                                web.date(from),
                                web.date(until),
                                adults,
                                children,
                                quantity,
                                typeId,
                                readyNow));
    }

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('booking.create')")
    @ResponseBody
    public ResponseEntity<com.example.hotelmanagement.modules.pricing.dto.PricingModels.Quote>
            quote(
                    @RequestParam List<Long> typeId,
                    @RequestParam List<String> from,
                    @RequestParam List<String> until,
                    @RequestParam List<Integer> adults,
                    @RequestParam List<Integer> children,
                    @RequestParam(required = false) Long customerId,
                    @RequestParam(required = false) String voucher) {
        int size = typeId.size();
        db.require(
                size > 0
                        && size <= 12
                        && from.size() == size
                        && until.size() == size
                        && adults.size() == size
                        && children.size() == size,
                "input");
        var rooms = new ArrayList<BookingService.RoomInput>();
        for (int i = 0; i < size; i++)
            rooms.add(
                    new BookingService.RoomInput(
                            typeId.get(i),
                            web.date(from.get(i)),
                            web.date(until.get(i)),
                            adults.get(i),
                            children.get(i)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        bookings.preview(
                                new BookingService.Create(
                                        null,
                                        customerId,
                                        null,
                                        null,
                                        null,
                                        "WALK_IN",
                                        voucher,
                                        null,
                                        rooms)));
    }

    @PostMapping("/customers/search")
    @PreAuthorize("hasAuthority('booking.create') and hasAuthority('customer.read')")
    @ResponseBody
    public ResponseEntity<List<BookingDeskOperations.CustomerMatch>> customers(
            @RequestParam String q, @RequestParam(defaultValue = "CONTACT") String mode) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(desk.customers(q, mode));
    }

    @GetMapping("/{id}/rooms/{line}/candidates")
    @PreAuthorize("hasAuthority('booking.update.any') and hasAuthority('booking.read.any')")
    @ResponseBody
    public ResponseEntity<List<RoomAllocationService.Candidate>> candidates(
            @PathVariable long id,
            @PathVariable long line,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) String until) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(desk.candidates(id, line, typeId, web.date(until)));
    }

    @PostMapping("/{id}/assign-room")
    @PreAuthorize("hasAuthority('booking.update.any')")
    public String assign(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam long room,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> desk.assign(id, line, room, reason), flash);
        return "redirect:/admin/bookings/" + id;
    }

    @GetMapping("/{id}/rooms/{line}/change-preview")
    @PreAuthorize("hasAuthority('booking.update.any') and hasAuthority('booking.read.any')")
    @ResponseBody
    public ResponseEntity<BookingChangeService.Review> preview(
            @PathVariable long id,
            @PathVariable long line,
            @RequestParam String kind,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) Long physicalRoom) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        changes.preview(
                                id,
                                line,
                                new BookingChangeService.Change(
                                        kind, web.date(from), web.date(until), physicalRoom)));
    }

    @GetMapping("/{id}/rooms/{line}/walk-in-preview")
    @PreAuthorize(
            "hasAuthority('booking.confirm') and hasAuthority('booking.checkin') and"
                    + " hasAuthority('booking.read.any')")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> walkInPreview(
            @PathVariable long id, @PathVariable long line) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        Map.of(
                                "preview",
                                stays.previewWalkIn(id, line),
                                "key",
                                OperationsStore.code("")));
    }

    @PostMapping("/{id}/walk-in-checkin")
    @PreAuthorize("hasAuthority('booking.confirm') and hasAuthority('booking.checkin')")
    public String walkIn(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam String reason,
            @RequestParam String key,
            @RequestParam(defaultValue = "BANK_TRANSFER") String method,
            @RequestParam(defaultValue = "0") BigDecimal amount,
            @RequestParam List<String> guestName,
            @RequestParam List<String> guestType,
            @RequestParam List<String> documentType,
            @RequestParam List<String> documentNumber,
            @RequestParam(required = false) List<String> dateOfBirth,
            @RequestParam(required = false) List<String> nationality,
            RedirectAttributes flash) {
        web.mutate(
                () -> {
                    var guests =
                            StayGuestForms.parse(
                                    db,
                                    guestName,
                                    guestType,
                                    documentType,
                                    documentNumber,
                                    dateOfBirth,
                                    nationality);
                    desk.walkIn(id, line, guests, reason, key, method, amount);
                },
                flash);
        return "redirect:/admin/bookings/" + id;
    }

    @GetMapping("/{id}/rooms/{line}/no-show-preview")
    @PreAuthorize("hasAuthority('booking.no_show') and hasAuthority('booking.read.any')")
    @ResponseBody
    public ResponseEntity<BookingService.NoShowReview> noShow(
            @PathVariable long id, @PathVariable long line) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(bookings.previewNoShow(id, line));
    }

    @ExceptionHandler(value = AppException.class, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> error(AppException error) {
        return ResponseEntity.status(error.getErrorCode().getStatus())
                .body(Map.of("message", error.getMessage()));
    }
}
