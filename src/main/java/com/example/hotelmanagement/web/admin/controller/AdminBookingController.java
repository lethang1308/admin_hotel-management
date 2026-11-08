package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.booking.BookingService.*;
import com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue;
import com.example.hotelmanagement.modules.invoice.InvoiceService;
import com.example.hotelmanagement.modules.payment.FrontDeskPaymentService;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Controller
@RequestMapping("/admin/bookings")
@RequiredArgsConstructor
public class AdminBookingController {
    private final BookingService bookings;
    private final StayService stays;
    private final BookingChangeService changes;
    private final OperationsQueries queries;
    private final OperationsStore db;
    private final OperationsWeb web;
    private final CmsMessages messages;
    private final HotelServiceCatalogue services;
    private final FrontDeskPaymentService payments;
    private final InvoiceService invoices;
    private final BookingAvailabilityService availability;
    private final GuestRequestService guestRequests;
    private final com.example.hotelmanagement.modules.reception.GuestMessageService guestMessages;

    @GetMapping
    @PreAuthorize("hasAuthority('booking.read.any')")
    public String list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "ALL") String quick,
            HttpServletRequest request,
            Model model) {
        web.types(model);
        web.page(
                queries.bookings(
                        q, status, source, typeId, web.date(from), web.date(until), page, quick),
                request,
                model);
        model.addAttribute(
                "statuses",
                List.of(
                        "PENDING",
                        "CONFIRMED",
                        "CHECKED_IN",
                        "CHECKED_OUT",
                        "CANCELLED",
                        "REJECTED",
                        "NO_SHOW"));
        model.addAttribute("sources", List.of("ONLINE", "PHONE", "WALK_IN"));
        model.addAttribute("quick", quick);
        model.addAttribute("quickCounts", queries.quickCounts());
        var assigned = new HashMap<Long, List<OperationsStore.Row>>();
        var listed =
                (com.example.hotelmanagement.modules.booking.OperationsQueries.Page)
                        model.getAttribute("page");
        for (var b : listed.rows())
            assigned.put(
                    b.id("id"),
                    db.rows(
                            """
                SELECT br.id,br.status,br.check_in_date,br.check_out_date,br.room_type_name_snapshot,r.room_number,r.status AS room_status
                FROM booking_rooms br LEFT JOIN room_allocations a ON a.id=(
                    SELECT MAX(ra.id) FROM room_allocations ra WHERE ra.booking_room_id=br.id)
                LEFT JOIN rooms r ON r.id=a.room_id WHERE br.booking_id=? ORDER BY br.line_number
                """,
                            b.id("id")));
        model.addAttribute("assignments", assigned);
        model.addAttribute("today", db.today());
        return "admin/operations/bookings";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('booking.read.any')")
    public String detail(@PathVariable long id, Model model, Authentication authentication) {
        var detail = queries.detail(id);
        model.addAttribute("d", detail);
        model.addAttribute("guestPreferences", guestRequests.preferences(id));
        model.addAttribute("guestTags", GuestRequestService.TAGS);
        model.addAttribute("emailKey", OperationsStore.code(""));
        if (authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("notification.manage")))
            model.addAttribute("guestMessages", guestMessages.history(id));
        model.addAttribute(
                "inHouseLines",
                detail.rooms().stream()
                        .filter(r -> "CHECKED_IN".equals(r.s("status")))
                        .map(r -> r.id("id"))
                        .toList());
        web.key(model);
        model.addAttribute("refundKey", OperationsStore.code(""));
        var serviceKeys = new HashMap<Long, String>();
        detail.rooms().forEach(r -> serviceKeys.put(r.id("id"), OperationsStore.code("")));
        model.addAttribute("serviceKeys", serviceKeys);
        var permissions = authentication.getAuthorities().stream().map(Object::toString).toList();
        if (permissions.contains("booking.update.any"))
            model.addAttribute(
                    "physical",
                    db.rows(
                            """
                    SELECT r.id,r.room_number,t.name AS type_name FROM rooms r JOIN room_types t ON t.id=r.room_type_id
                    WHERE r.status<>'INACTIVE' AND t.is_active=TRUE ORDER BY r.room_number
                    """));
        if (permissions.contains("service.usage.manage"))
            model.addAttribute(
                    "services",
                    db.rows(
                            """
                    SELECT s.id,s.name,s.unit,s.unit_price FROM services s JOIN service_categories c ON c.id=s.category_id
                    WHERE s.is_active=TRUE AND c.is_active=TRUE ORDER BY s.name
                    """));
        model.addAttribute("today", db.today());
        web.types(model);
        return "admin/operations/booking";
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAuthority('booking.read.any')")
    public String calendar(
            @RequestParam(defaultValue = "week") String view,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) Long typeId,
            Model model) {
        LocalDate start = from == null || from.isBlank() ? db.today() : web.date(from);
        long days =
                switch (view) {
                    case "day" -> 1;
                    case "week" -> 7;
                    case "month" -> start.lengthOfMonth();
                    default -> 7;
                };
        if (view.equals("month")) start = start.withDayOfMonth(1);
        web.types(model);
        model.addAttribute("calendar", queries.calendar(start, start.plusDays(days), typeId));
        model.addAttribute(
                "previousDate",
                view.equals("month") ? start.minusMonths(1) : start.minusDays(days));
        return "admin/operations/calendar";
    }

    @GetMapping("/create")
    @PreAuthorize("hasAuthority('booking.create')")
    public String createPage(
            @RequestParam(required = false) String customerSearch,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "1") int adults,
            @RequestParam(defaultValue = "0") int children,
            @RequestParam(defaultValue = "1") int quantity,
            Model model) {
        var start = from == null || from.isBlank() ? db.today() : web.date(from);
        var end = until == null || until.isBlank() ? start.plusDays(1) : web.date(until);
        availability.validate(start, end, adults, children, quantity);
        var input =
                new Create(
                        OperationsStore.code(""),
                        null,
                        null,
                        null,
                        null,
                        "WALK_IN",
                        null,
                        null,
                        java.util.stream.IntStream.range(0, quantity)
                                .mapToObj(
                                        i ->
                                                new RoomInput(
                                                        typeId == null ? 0 : typeId,
                                                        start,
                                                        end,
                                                        adults,
                                                        children))
                                .toList());
        prepareCreate(input, customerSearch, model);
        return "admin/operations/create";
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('booking.create')")
    public String create(
            @RequestParam String requestKey,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            @RequestParam String source,
            @RequestParam(required = false) String voucher,
            @RequestParam(required = false) String notes,
            @RequestParam List<Long> typeId,
            @RequestParam List<String> from,
            @RequestParam List<String> until,
            @RequestParam List<Integer> adults,
            @RequestParam List<Integer> children,
            @RequestParam(required = false) BigDecimal expected,
            Model model,
            RedirectAttributes flash) {
        int count = typeId.size();
        db.require(
                count > 0
                        && count <= 12
                        && from.size() == count
                        && until.size() == count
                        && adults.size() == count
                        && children.size() == count,
                "input");
        var rooms = new ArrayList<RoomInput>();
        for (int i = 0; i < count; i++)
            rooms.add(
                    new RoomInput(
                            typeId.get(i),
                            web.date(from.get(i)),
                            web.date(until.get(i)),
                            adults.get(i),
                            children.get(i)));
        var input =
                new Create(
                        requestKey, customerId, name, phone, email, source, voucher, notes, rooms);
        try {
            if (expected != null) {
                long id = web.retry(() -> bookings.create(input, expected));
                flash.addFlashAttribute("successMessage", messages.text("cms.saved"));
                if ("WALK_IN".equals(source)
                        && rooms.stream().anyMatch(r -> r.from().equals(db.today())))
                    flash.addFlashAttribute("walkInCreated", true);
                return redirect(id);
            }
            model.addAttribute("quote", bookings.preview(input));
        } catch (AppException error) {
            model.addAttribute("errorMessage", error.getMessage());
        } catch (DataIntegrityViolationException error) {
            model.addAttribute("errorMessage", messages.text("ops.error.concurrent"));
        } catch (PessimisticLockingFailureException error) {
            model.addAttribute("errorMessage", messages.text("ops.error.retry"));
        }
        prepareCreate(input, null, model);
        return "admin/operations/create";
    }

    private void prepareCreate(Create input, String search, Model model) {
        web.types(model);
        model.addAttribute("f", input);
        if (search != null && !search.isBlank()) {
            db.require(search.length() <= 150, "input");
            String like = OperationsStore.like(search);
            model.addAttribute(
                    "customers",
                    db.rows(
                            """
                    SELECT id,customer_code,full_name,phone FROM customers
                    WHERE LOWER(full_name) LIKE ? ESCAPE '!' OR LOWER(customer_code) LIKE ? ESCAPE '!'
                        OR LOWER(phone) LIKE ? ESCAPE '!' OR LOWER(email) LIKE ? ESCAPE '!'
                    ORDER BY id DESC LIMIT 50
                    """,
                            like,
                            like,
                            like,
                            like));
        }
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('booking.confirm')")
    public String confirm(
            @PathVariable long id, @RequestParam String reason, RedirectAttributes flash) {
        web.mutate(() -> bookings.confirm(id, reason), flash);
        return redirect(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('booking.confirm')")
    public String reject(
            @PathVariable long id, @RequestParam String reason, RedirectAttributes flash) {
        web.mutate(() -> bookings.reject(id, reason), flash);
        return redirect(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('booking.cancel.any')")
    public String cancel(
            @PathVariable long id,
            @RequestParam(required = false) Long line,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> bookings.cancel(id, line, reason), flash);
        return redirect(id);
    }

    @PostMapping("/{id}/no-show")
    @PreAuthorize("hasAuthority('booking.no_show')")
    public String noShow(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> bookings.noShow(id, line, reason), flash);
        return redirect(id);
    }

    @PostMapping("/{id}/checkin")
    @PreAuthorize("hasAuthority('booking.checkin')")
    public String checkin(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam String reason,
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
                    stays.checkIn(id, line, guests, reason);
                },
                flash);
        return redirect(id);
    }

    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasAuthority('booking.checkout')")
    public String checkout(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> stays.checkOut(id, line, reason), flash);
        return redirect(id);
    }

    @PostMapping("/{id}/changes")
    @PreAuthorize("hasAuthority('booking.update.any')")
    public String change(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam String kind,
            @RequestParam String reason,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) Long physicalRoom,
            @RequestParam(required = false) Long reviewedVersion,
            @RequestParam(required = false) BigDecimal reviewedDifference,
            RedirectAttributes flash) {
        web.mutate(
                () ->
                        changes.requestReviewed(
                                id,
                                line,
                                new BookingChangeService.Change(
                                        kind, web.date(from), web.date(until), physicalRoom),
                                reason,
                                reviewedVersion,
                                reviewedDifference),
                flash);
        return redirect(id) + "#changes";
    }

    @PostMapping("/{id}/changes/{change}/decide")
    @PreAuthorize("hasAuthority('booking.update.any')")
    public String decide(
            @PathVariable long id,
            @PathVariable long change,
            @RequestParam boolean approve,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> changes.decide(id, change, approve, reason), flash);
        return redirect(id) + "#changes";
    }

    @PostMapping("/{id}/services")
    @PreAuthorize("hasAuthority('service.usage.manage')")
    public String use(
            @PathVariable long id,
            @RequestParam long line,
            @RequestParam long service,
            @RequestParam BigDecimal quantity,
            @RequestParam String key,
            @RequestParam(required = false) String notes,
            RedirectAttributes flash) {
        web.mutate(() -> services.use(id, line, service, quantity, key, notes), flash);
        return redirect(id) + "#usages";
    }

    @PostMapping("/{id}/services/{usage}/void")
    @PreAuthorize("hasAuthority('service.usage.manage')")
    public String voidUsage(
            @PathVariable long id,
            @PathVariable long usage,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> services.voidUsage(id, usage, reason), flash);
        return redirect(id) + "#usages";
    }

    @PostMapping("/{id}/collect")
    @PreAuthorize("hasAuthority('payment.collect')")
    public String collect(
            @PathVariable long id,
            @RequestParam String key,
            @RequestParam String kind,
            @RequestParam String method,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String reason,
            RedirectAttributes flash) {
        web.mutate(() -> payments.collect(id, key, kind, method, amount, reason), flash);
        return redirect(id) + "#payments";
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('payment.refund')")
    public String refund(
            @PathVariable long id,
            @RequestParam String key,
            @RequestParam long parent,
            @RequestParam String method,
            @RequestParam BigDecimal amount,
            @RequestParam String reason,
            RedirectAttributes flash) {
        web.mutate(() -> payments.refund(id, key, parent, method, amount, reason), flash);
        return redirect(id) + "#payments";
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize("hasAuthority('invoice.manage')")
    public String invoice(
            @PathVariable long id,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String taxCode,
            @RequestParam(required = false) String company,
            RedirectAttributes flash) {
        web.mutate(() -> invoices.issue(id, address, taxCode, company), flash);
        return redirect(id) + "#invoices";
    }

    private String redirect(long id) {
        return "redirect:/admin/bookings/" + id;
    }
}
