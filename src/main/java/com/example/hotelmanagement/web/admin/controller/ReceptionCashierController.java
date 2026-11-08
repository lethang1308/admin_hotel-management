package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue;
import com.example.hotelmanagement.modules.payment.*;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/receptionist/cashier")
public class ReceptionCashierController {
    private final FrontDeskCashierService cashier;
    private final RefundRequestService refunds;
    private final FrontDeskPaymentService payments;
    private final HotelServiceCatalogue catalogue;
    private final BankTransferQr bank;
    private final OperationsWeb web;
    private final OperationsStore db;
    private final com.example.hotelmanagement.common.util.CmsMessages messages;

    @GetMapping
    @PreAuthorize("hasAuthority('booking.read.any')")
    public String page(Model model, Authentication auth) {
        if (auth.getAuthorities().stream()
                .anyMatch(
                        a ->
                                Set.of("payment.collect", "payment.refund")
                                        .contains(a.getAuthority())))
            model.addAttribute("refundQueue", refunds.queue());
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("hotel.manage")))
            model.addAttribute("bank", bank.settings());
        return "admin/cashier";
    }

    @GetMapping("/{id}")
    @ResponseBody
    @PreAuthorize("hasAuthority('booking.read.any')")
    public ResponseEntity<FrontDeskCashierService.Snapshot> snapshot(@PathVariable long id) {
        return data(cashier.snapshot(id));
    }

    public record Usage(
            long line,
            long service,
            BigDecimal quantity,
            String key,
            String notes,
            BigDecimal price,
            BigDecimal tax) {}

    @PostMapping("/{id}/service")
    @ResponseBody
    @PreAuthorize("hasAuthority('service.usage.manage')")
    public ResponseEntity<Map<String, Long>> service(@PathVariable long id, @RequestBody Usage f) {
        return data(
                Map.of(
                        "id",
                        cashier.service(
                                id,
                                f.line(),
                                f.service(),
                                f.quantity(),
                                f.key(),
                                f.notes(),
                                f.price(),
                                f.tax())));
    }

    public record Reason(String reason) {}

    @PostMapping("/{id}/services/{usage}/void")
    @ResponseBody
    @PreAuthorize("hasAuthority('service.usage.manage')")
    public ResponseEntity<Map<String, Boolean>> voidUsage(
            @PathVariable long id, @PathVariable long usage, @RequestBody Reason f) {
        catalogue.voidUsage(id, usage, f.reason());
        return data(Map.of("saved", true));
    }

    @PostMapping("/{id}/collect")
    @ResponseBody
    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public ResponseEntity<Map<String, Long>> collect(
            @PathVariable long id,
            @RequestParam String key,
            @RequestParam String kind,
            @RequestParam String method,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String reference,
            @RequestParam String token,
            @RequestParam(required = false) MultipartFile proof) {
        return data(
                Map.of(
                        "id",
                        cashier.collect(
                                id, key, kind, method, amount, reason, reference, token, proof)));
    }

    @GetMapping("/{id}/bank-qr")
    @ResponseBody
    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public ResponseEntity<BankTransferQr.Code> qr(
            @PathVariable long id, @RequestParam BigDecimal amount) {
        return data(bank.code(id, amount));
    }

    public record Refund(
            String key,
            long parent,
            BigDecimal amount,
            String method,
            String reason,
            String reference,
            String token) {}

    @PostMapping("/{id}/refund")
    @ResponseBody
    @PreAuthorize("hasAuthority('payment.refund')")
    public ResponseEntity<Map<String, Long>> refund(@PathVariable long id, @RequestBody Refund f) {
        return data(
                Map.of(
                        "id",
                        cashier.refund(
                                id,
                                f.key(),
                                f.parent(),
                                f.method(),
                                f.amount(),
                                f.reason(),
                                f.reference(),
                                f.token())));
    }

    @PostMapping("/{id}/refund-request")
    @ResponseBody
    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public ResponseEntity<Map<String, Long>> request(@PathVariable long id, @RequestBody Refund f) {
        return data(
                Map.of(
                        "id",
                        refunds.request(
                                id, f.key(), f.parent(), f.amount(), f.method(), f.reason())));
    }

    public record Decision(boolean approve, String note, String reference) {}

    @PostMapping("/{id}/refund-requests/{request}/decide")
    @ResponseBody
    @PreAuthorize("hasAuthority('payment.refund')")
    public ResponseEntity<Map<String, Boolean>> decide(
            @PathVariable long id, @PathVariable long request, @RequestBody Decision f) {
        refunds.decide(id, request, f.approve(), f.note(), f.reference());
        return data(Map.of("saved", true));
    }

    @PostMapping("/{id}/refund-requests/{request}/withdraw")
    @ResponseBody
    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public ResponseEntity<Map<String, Boolean>> withdraw(
            @PathVariable long id, @PathVariable long request) {
        refunds.withdraw(id, request);
        return data(Map.of("saved", true));
    }

    public record Invoice(String company, String address, String taxCode, String token) {}

    @PostMapping("/{id}/invoice")
    @ResponseBody
    @PreAuthorize("hasAuthority('invoice.manage')")
    public ResponseEntity<Map<String, Long>> invoice(
            @PathVariable long id, @RequestBody Invoice f) {
        return data(
                Map.of(
                        "id",
                        cashier.invoice(id, f.company(), f.address(), f.taxCode(), f.token())));
    }

    public record Shift(String action, BigDecimal cash, String notes) {}

    @PostMapping("/shift")
    @ResponseBody
    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','report.shift')")
    public ResponseEntity<Map<String, Boolean>> shift(@RequestBody Shift f) {
        db.require("open".equals(f.action()) || "close".equals(f.action()), "input");
        if ("open".equals(f.action())) payments.open(f.cash(), f.notes());
        else payments.close(f.cash(), f.notes());
        return data(Map.of("saved", true));
    }

    @PostMapping("/bank")
    @PreAuthorize("hasAuthority('hotel.manage')")
    public String bank(
            @RequestParam(required = false) String bin,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String name,
            @RequestParam int revision,
            RedirectAttributes flash) {
        web.mutate(
                () -> bank.save(new BankTransferQr.Settings(bin, account, name, revision)), flash);
        return "redirect:/admin/receptionist/cashier#bank";
    }

    @GetMapping("/proofs/{payment}")
    @PreAuthorize(
            "hasAuthority('booking.read.any') and"
                + " hasAnyAuthority('payment.collect','payment.refund')")
    public ResponseEntity<byte[]> proof(@PathVariable long payment) {
        var file = cashier.proof(payment);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.type()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=payment-proof")
                .body(file.bytes());
    }

    @ExceptionHandler(
            value = {
                org.springframework.http.converter.HttpMessageNotReadableException.class,
                org.springframework.web.bind.MissingServletRequestParameterException.class,
                org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", messages.text("ops.error.input")));
    }

    @ExceptionHandler(
            value = org.springframework.security.access.AccessDeniedException.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> forbidden(Exception e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", messages.text("cashier.noRefundPermission")));
    }

    private <T> ResponseEntity<T> data(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    @ExceptionHandler(value = AppException.class, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> error(AppException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(
            value = DataIntegrityViolationException.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", messages.text("ops.error.referenceDuplicate")));
    }
}
