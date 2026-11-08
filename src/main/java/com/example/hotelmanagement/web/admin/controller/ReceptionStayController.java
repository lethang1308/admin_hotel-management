package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.invoice.InvoiceService;
import com.example.hotelmanagement.modules.reception.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class ReceptionStayController {
    private final StayWorkflowService workflow;
    private final ReceptionBookingLookup lookup;
    private final OperationsStore db;
    private final InvoiceService invoices;
    private final OperationsWeb web;

    @GetMapping("/admin/receptionist/stays")
    @PreAuthorize("hasAuthority('booking.read.any')")
    public String page(Model model, Authentication auth) {
        if (auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("housekeeping.manage")))
            model.addAttribute(
                    "cleaning",
                    db.rows(
                            """
            SELECT t.*,r.room_number,r.status AS room_status FROM housekeeping_tasks t JOIN rooms r ON r.id=t.room_id
            WHERE t.status IN ('PENDING','IN_PROGRESS','COMPLETED') ORDER BY t.created_at,t.id LIMIT 50
            """));
        return "admin/stays";
    }

    @GetMapping("/admin/bookings/{id}/checkin-preview")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.checkin')")
    public ResponseEntity<StayWorkflowService.Bill> checkinPreview(
            @PathVariable long id, @RequestParam long line) {
        return noCache(workflow.checkinPreview(id, line));
    }

    @GetMapping("/admin/bookings/{id}/checkout-preview")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.checkout')")
    public ResponseEntity<StayWorkflowService.Bill> checkoutPreview(
            @PathVariable long id, @RequestParam List<Long> line) {
        return noCache(workflow.checkoutPreview(id, line));
    }

    public record Checkin(
            long line,
            List<StayService.Guest> guests,
            String reason,
            String key,
            String method,
            BigDecimal amount,
            Long cleanRoom,
            BigDecimal expectedDebt,
            long version) {}

    @PostMapping("/admin/receptionist/stays/{id}/checkin")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.checkin')")
    public ResponseEntity<StayWorkflowService.Receipt> checkin(
            @PathVariable long id, @RequestBody Checkin f) {
        return noCache(
                workflow.checkin(
                        id,
                        f.line(),
                        f.guests(),
                        f.reason(),
                        f.key(),
                        f.method(),
                        f.amount(),
                        f.cleanRoom(),
                        f.expectedDebt(),
                        f.version()));
    }

    public record Checkout(
            List<Long> lines,
            String reason,
            String key,
            String method,
            BigDecimal amount,
            String paymentAction,
            Long parent,
            BigDecimal expectedDebt,
            long version,
            boolean issueInvoice,
            String buyerAddress,
            String taxCode,
            String buyerCompany) {
        public Checkout(
                List<Long> lines,
                String reason,
                String key,
                String method,
                BigDecimal amount,
                String paymentAction,
                Long parent,
                BigDecimal expectedDebt,
                long version,
                boolean issueInvoice,
                String buyerAddress,
                String taxCode) {
            this(
                    lines,
                    reason,
                    key,
                    method,
                    amount,
                    paymentAction,
                    parent,
                    expectedDebt,
                    version,
                    issueInvoice,
                    buyerAddress,
                    taxCode,
                    null);
        }
    }

    @PostMapping("/admin/receptionist/stays/{id}/checkout")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.checkout')")
    public ResponseEntity<StayWorkflowService.Receipt> checkout(
            @PathVariable long id, @RequestBody Checkout f) {
        return noCache(
                workflow.checkoutWithCompany(
                        id,
                        f.lines(),
                        f.reason(),
                        f.key(),
                        f.method(),
                        f.amount(),
                        f.paymentAction(),
                        f.parent(),
                        f.expectedDebt(),
                        f.version(),
                        f.issueInvoice(),
                        f.buyerAddress(),
                        f.taxCode(),
                        f.buyerCompany()));
    }

    public record Search(String q, String mode) {}

    @PostMapping("/admin/receptionist/stays/lookup")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.read.any')")
    public ResponseEntity<List<ReceptionBookingLookup.Match>> find(@RequestBody Search f) {
        return noCache(lookup.find(f.q(), f.mode()));
    }

    @PostMapping("/admin/receptionist/stays/qr-image")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.qr.scan')")
    public ResponseEntity<List<ReceptionBookingLookup.Match>> image(
            @RequestParam MultipartFile image) throws IOException {
        db.require(image.getSize() <= 5 * 1024 * 1024, "qr");
        return noCache(lookup.image(image.getBytes()));
    }

    @PostMapping("/admin/receptionist/stays/{id}/qr")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.qr.scan')")
    public ResponseEntity<ReceptionBookingLookup.Issued> issue(@PathVariable long id) {
        return noCache(lookup.issue(id));
    }

    public record Priority(long booking, long line, String reason) {}

    @PostMapping("/admin/receptionist/stays/priority")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.checkin') and hasAuthority('housekeeping.manage')")
    public ResponseEntity<Map<String, Long>> priority(@RequestBody Priority f) {
        return noCache(Map.of("task", workflow.priority(f.booking(), f.line(), f.reason())));
    }

    @PostMapping("/admin/receptionist/stays/clean")
    @PreAuthorize("hasAuthority('housekeeping.manage') and hasAuthority('room.status.update')")
    public String clean(
            @RequestParam long task,
            @RequestParam String action,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> workflow.clean(task, action, reason), flash);
        return "redirect:/admin/receptionist/stays#cleaning";
    }

    @PostMapping("/admin/bookings/{id}/invoices/issue")
    @ResponseBody
    @PreAuthorize("hasAuthority('invoice.manage')")
    public ResponseEntity<Map<String, Long>> invoice(
            @PathVariable long id,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String taxCode,
            @RequestParam(required = false) String company) {
        return noCache(Map.of("invoice", invoices.issue(id, address, taxCode, company)));
    }

    private <T> ResponseEntity<T> noCache(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    @ExceptionHandler(value = AppException.class, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> error(AppException error) {
        return ResponseEntity.status(error.getErrorCode().getStatus())
                .body(Map.of("message", error.getMessage()));
    }
}
