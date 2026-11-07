package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.modules.booking.OperationsQueries;
import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue;
import com.example.hotelmanagement.modules.invoice.InvoiceService;
import com.example.hotelmanagement.modules.payment.FrontDeskPaymentService;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminOperationsController {
    private final OperationsQueries queries;
    private final OperationsStore db;
    private final OperationsWeb web;
    private final HotelServiceCatalogue catalogue;
    private final FrontDeskPaymentService payments;
    private final InvoiceService invoices;

    @GetMapping("/cancellations")
    @PreAuthorize("hasAuthority('booking.cancel.any')")
    public String cancellations(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Long actor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {
        web.page(
                queries.cancellations(kind, actor, web.date(from), web.date(until), page),
                request,
                model);
        model.addAttribute(
                "actors",
                db.rows(
                        """
                SELECT DISTINCT u.id,u.email FROM users u JOIN booking_cancellations c ON c.processed_by=u.id ORDER BY u.email
                """));
        return "admin/operations/cancellations";
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    public String payments(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {
        web.page(
                queries.payments(kind, status, method, web.date(from), web.date(until), page),
                request,
                model);
        model.addAttribute("shifts", queries.shifts());
        return "admin/operations/payments";
    }

    @PostMapping("/payments/shift/{action}")
    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    public String shift(
            @PathVariable String action,
            @RequestParam BigDecimal cash,
            @RequestParam(required = false) String notes,
            RedirectAttributes flash) {
        web.mutate(
                () -> {
                    db.require(action.equals("open") || action.equals("close"), "input");
                    if (action.equals("open")) payments.open(cash, notes);
                    else payments.close(cash, notes);
                },
                flash);
        return "redirect:/admin/payments";
    }

    @GetMapping("/debts")
    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    public String debts(
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {
        web.page(queries.debts(direction, page), request, model);
        return "admin/operations/debts";
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('invoice.manage')")
    public String invoices(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int page,
            HttpServletRequest request,
            Model model) {
        web.page(
                queries.invoices(q, status, web.date(from), web.date(until), page), request, model);
        return "admin/operations/invoices";
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('invoice.manage')")
    public String invoice(@PathVariable long id, Model model) {
        model.addAllAttributes(queries.invoice(id));
        return "admin/operations/invoice";
    }

    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize("hasAuthority('invoice.manage')")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) {
        var download = invoices.download(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.name())
                                .build()
                                .toString())
                .body(download.data());
    }

    @PostMapping("/invoices/{id}/void")
    @PreAuthorize("hasAuthority('invoice.manage')")
    public String voidInvoice(
            @PathVariable long id, @RequestParam String reason, RedirectAttributes flash) {
        web.mutate(() -> invoices.voidInvoice(id, reason), flash);
        return "redirect:/admin/invoices/" + id;
    }

    @GetMapping({"/services", "/service-categories"})
    @PreAuthorize("hasAnyAuthority('service.read','service.manage')")
    public String services(
            @RequestParam(required = false) Long item,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            Model model,
            Authentication auth) {
        model.addAttribute("items", queries.services());
        model.addAttribute("categories", db.rows("SELECT * FROM service_categories ORDER BY name"));
        if (auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("service.manage"))) {
            model.addAttribute("top", queries.topServices(web.date(from), web.date(until)));
            if (item != null)
                model.addAttribute("item", db.one("SELECT * FROM services WHERE id=?", item));
            if (category != null)
                model.addAttribute(
                        "category",
                        db.one("SELECT * FROM service_categories WHERE id=?", category));
        }
        return "admin/operations/services";
    }

    @PostMapping("/services")
    @PreAuthorize("hasAuthority('service.manage')")
    public String saveService(
            @RequestParam(required = false) Long id,
            @RequestParam long categoryId,
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam String unit,
            @RequestParam BigDecimal unitPrice,
            @RequestParam BigDecimal tax,
            @RequestParam(defaultValue = "false") boolean active,
            RedirectAttributes flash) {
        web.mutate(
                () ->
                        catalogue.save(
                                id,
                                new HotelServiceCatalogue.Item(
                                        categoryId,
                                        code,
                                        name,
                                        description,
                                        unit,
                                        unitPrice,
                                        tax,
                                        active)),
                flash);
        return "redirect:/admin/services";
    }

    @PostMapping("/service-categories")
    @PreAuthorize("hasAuthority('service.manage')")
    public String saveCategory(
            @RequestParam(required = false) Long id,
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam(defaultValue = "false") boolean active,
            RedirectAttributes flash) {
        web.mutate(() -> catalogue.saveCategory(id, code, name, active), flash);
        return "redirect:/admin/services";
    }

    @PostMapping({"/services/{id}/delete", "/service-categories/{id}/delete"})
    @PreAuthorize("hasAuthority('service.manage')")
    public String delete(
            @PathVariable long id, HttpServletRequest request, RedirectAttributes flash) {
        web.mutate(
                () ->
                        catalogue.delete(
                                id, request.getRequestURI().contains("/service-categories/")),
                flash);
        return "redirect:/admin/services";
    }

    @GetMapping("/inventory/reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public String reconcile(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            Model model) {
        var start = from == null || from.isBlank() ? db.today() : web.date(from);
        var end = until == null || until.isBlank() ? start.plusDays(14) : web.date(until);
        model.addAttribute("rows", queries.reconcile(start, end));
        model.addAttribute("from", start);
        model.addAttribute("until", end);
        return "admin/operations/reconcile";
    }
}
