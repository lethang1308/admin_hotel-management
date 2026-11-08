package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue;
import com.example.hotelmanagement.modules.payment.FrontDeskPaymentService;
import com.example.hotelmanagement.modules.reception.*;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class ReceptionDashboardController {
    private final ReceptionDashboardService dashboard;
    private final ReceptionActionService actions;
    private final ReceptionNotificationService notifications;
    private final ReceptionNotificationPublisher publisher;
    private final BookingService bookings;
    private final StayService stays;
    private final FrontDeskPaymentService payments;
    private final HotelServiceCatalogue services;
    private final OperationsStore db;
    private final OperationsWeb web;

    @GetMapping("/admin/receptionist")
    @PreAuthorize("hasAnyAuthority('booking.read.any','report.shift')")
    public String page(Model model) {
        model.addAttribute("desk", dashboard.snapshot());
        return "admin/receptionist";
    }

    @GetMapping("/admin/receptionist/fragment")
    @PreAuthorize("hasAnyAuthority('booking.read.any','report.shift')")
    public String fragment(Model model) {
        model.addAttribute("desk", dashboard.snapshot());
        return "admin/receptionist :: live";
    }

    @GetMapping("/admin/api/receptionist/dashboard")
    @PreAuthorize("hasAnyAuthority('booking.read.any','report.shift')")
    @ResponseBody
    public ResponseEntity<ReceptionDashboardService.Dashboard> data() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dashboard.snapshot());
    }

    @GetMapping("/admin/api/receptionist/rooms/{line}/preview")
    @PreAuthorize("hasAuthority('booking.read.any')")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> preview(
            @PathVariable long line,
            @RequestParam long booking,
            @RequestParam String action,
            Authentication auth) {
        db.require(Set.of("checkin", "checkout").contains(action), "input");
        var quote =
                action.equals("checkin")
                        ? stays.previewCheckIn(booking, line)
                        : stays.previewCheckOut(booking, line);
        var result = new HashMap<String, Object>();
        result.put("preview", quote);
        result.put("key", OperationsStore.code(""));
        result.put(
                "charges",
                db
                        .rows(
                                "SELECT description,amount,kind FROM booking_charges WHERE"
                                        + " booking_id=? AND status='POSTED' ORDER BY id LIMIT 100",
                                booking)
                        .stream()
                        .map(OperationsStore.Row::data)
                        .toList());
        if (auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("service.usage.manage"))) {
            result.put(
                    "services",
                    db
                            .rows(
                                    """
                    SELECT s.id,s.name,s.unit,s.unit_price FROM services s JOIN service_categories c ON c.id=s.category_id
                    WHERE s.is_active=TRUE AND c.is_active=TRUE ORDER BY s.name
                    """)
                            .stream()
                            .map(OperationsStore.Row::data)
                            .toList());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/admin/receptionist/shift/{action}")
    @PreAuthorize("hasAuthority('report.shift')")
    public String shift(
            @PathVariable String action,
            @RequestParam BigDecimal cash,
            @RequestParam(required = false) String notes,
            RedirectAttributes flash) {
        web.mutate(
                () -> {
                    db.require(Set.of("open", "close").contains(action), "input");
                    if (action.equals("open")) payments.open(cash, notes);
                    else payments.close(cash, notes);
                },
                flash);
        return "redirect:/admin/receptionist";
    }

    @PostMapping("/admin/receptionist/quick-confirm/{id}")
    @PreAuthorize("hasAuthority('booking.confirm')")
    public String confirm(
            @PathVariable long id, @RequestParam String reason, RedirectAttributes flash) {
        web.mutate(() -> bookings.confirm(id, reason), flash);
        return "redirect:/admin/receptionist";
    }

    @PostMapping("/admin/receptionist/quick-reject/{id}")
    @PreAuthorize("hasAuthority('booking.confirm')")
    public String reject(
            @PathVariable long id, @RequestParam String reason, RedirectAttributes flash) {
        web.mutate(() -> bookings.reject(id, reason), flash);
        return "redirect:/admin/receptionist";
    }

    @PostMapping("/admin/receptionist/no-show/{id}")
    @PreAuthorize("hasAuthority('booking.no_show')")
    public String noShow(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> bookings.noShow(id, line, reason), flash);
        return "redirect:/admin/receptionist";
    }

    @PostMapping("/admin/receptionist/quick-checkin")
    @PreAuthorize("hasAuthority('booking.checkin')")
    public String checkIn(
            @RequestParam long booking,
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
                    actions.checkIn(booking, line, guests, reason, key, method, amount);
                },
                flash);
        return "redirect:/admin/receptionist";
    }

    @PostMapping("/admin/receptionist/quick-checkout")
    @PreAuthorize("hasAuthority('booking.checkout')")
    public String checkOut(
            @RequestParam long booking,
            @RequestParam long line,
            @RequestParam String reason,
            @RequestParam String key,
            @RequestParam(defaultValue = "BANK_TRANSFER") String method,
            @RequestParam(defaultValue = "0") BigDecimal amount,
            @RequestParam BigDecimal expectedDebt,
            RedirectAttributes flash) {
        web.mutate(
                () ->
                        stays.checkOutAndCollect(
                                booking,
                                line,
                                reason,
                                new StayService.Settlement(key, method, amount, expectedDebt)),
                flash);
        return "redirect:/admin/receptionist";
    }

    @PostMapping("/admin/receptionist/services")
    @PreAuthorize("hasAuthority('service.usage.manage')")
    @ResponseBody
    public Map<String, Long> service(
            @RequestParam long booking,
            @RequestParam long line,
            @RequestParam long service,
            @RequestParam BigDecimal quantity,
            @RequestParam String key) {
        return Map.of("id", services.use(booking, line, service, quantity, key, null));
    }

    @GetMapping({"/admin/api/receptionist/stream", "/admin/api/receptionist/notifications/stream"})
    @PreAuthorize("hasAnyAuthority('notification.manage','booking.read.any')")
    public SseEmitter stream(Authentication auth, HttpServletRequest request) {
        return publisher.subscribe(
                (CustomUserDetails) auth.getPrincipal(), request.getSession(false));
    }

    @GetMapping("/admin/api/receptionist/notifications")
    @PreAuthorize("hasAnyAuthority('notification.manage','booking.read.any')")
    @ResponseBody
    public ResponseEntity<ReceptionNotificationService.Feed> feed() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.feed());
    }

    @PostMapping("/admin/api/receptionist/notifications/read")
    @PreAuthorize("hasAuthority('notification.manage')")
    @ResponseBody
    public Map<String, Boolean> read(@RequestParam List<Long> ids) {
        notifications.read(ids);
        return Map.of("saved", true);
    }

    @ExceptionHandler(value = AppException.class, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> jsonError(AppException error) {
        return ResponseEntity.status(error.getErrorCode().getStatus())
                .body(Map.of("message", error.getMessage()));
    }
}
