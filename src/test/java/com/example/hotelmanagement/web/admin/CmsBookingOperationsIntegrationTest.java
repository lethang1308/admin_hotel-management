package com.example.hotelmanagement.web.admin;

import static com.example.hotelmanagement.modules.booking.OperationsStore.code;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.booking.BookingService.*;
import com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue;
import com.example.hotelmanagement.modules.invoice.InvoiceService;
import com.example.hotelmanagement.modules.payment.*;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.support.CmsTestData;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmsBookingOperationsIntegrationTest {
    @TempDir static Path pdfs;

    @DynamicPropertySource
    static void testStorage(DynamicPropertyRegistry registry) {
        registry.add("app.invoice.directory", () -> pdfs.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperationsStore db;
    @Autowired UserService users;
    @Autowired BookingService bookings;
    @Autowired BookingChangeService changes;
    @Autowired StayService stays;
    @Autowired HotelServiceCatalogue catalogue;
    @Autowired FrontDeskPaymentService payments;
    @Autowired BookingLedger ledger;
    @Autowired InvoiceService invoices;
    @Autowired OperationsQueries queries;
    MockHttpSession admin;
    Authentication principal;
    long type, deluxe, floor, room, user;
    LocalDate today;

    @BeforeEach
    void setup() throws Exception {
        CmsTestData.clear(jdbc);
        user = user("ADMIN");
        admin = login("ADMIN");
        principal =
                ((org.springframework.security.core.context.SecurityContext)
                                admin.getAttribute("SPRING_SECURITY_CONTEXT"))
                        .getAuthentication();
        authenticate();
        today = db.today();
        type = db.one("SELECT id FROM room_types WHERE code='STD'").id("id");
        deluxe = db.one("SELECT id FROM room_types WHERE code='DLX'").id("id");
        db.update("UPDATE room_types SET base_price=800000,is_active=TRUE WHERE id=?", type);
        db.update("UPDATE room_types SET base_price=1200000,is_active=TRUE WHERE id=?", deluxe);
        db.update("UPDATE hotel_settings SET default_tax_percent=10 WHERE id=1");
        floor = db.insert("floors", "code,name,area_name,floor_number", "F1", "Tầng một", "A", 1);
        room = room("101", type);
    }

    @AfterEach
    void reset() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(principal);
    }

    private long user(String role) {
        return users.createUser(
                        CreateUserRequest.builder()
                                .fullName("Operations " + role)
                                .email(role.toLowerCase(Locale.ROOT) + "@ops.example.test")
                                .password("InitialPass123")
                                .roles(Set.of(role))
                                .build())
                .getId();
    }

    private MockHttpSession login(String role) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/admin/login")
                                        .with(csrf())
                                        .param(
                                                "email",
                                                role.toLowerCase(Locale.ROOT) + "@ops.example.test")
                                        .param("password", "InitialPass123"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    private long room(String number, long type) {
        return db.insert(
                "rooms", "room_number,room_type_id,floor_id,sell_from", number, type, floor, today);
    }

    private Create input(LocalDate from, LocalDate until) {
        return new Create(
                code(""),
                null,
                "Nguyễn Văn Thắng",
                "0912345678",
                "guest@example.test",
                "WALK_IN",
                null,
                null,
                List.of(new RoomInput(type, from, until, 1, 0)));
    }

    private long create(LocalDate from, LocalDate until) {
        var input = input(from, until);
        return bookings.create(input, bookings.preview(input).totalAmount());
    }

    private long line(long booking) {
        return db.one("SELECT id FROM booking_rooms WHERE booking_id=?", booking).id("id");
    }

    @Test
    void finalRoomConcurrentBookingsOnlyOneSucceedsAndDuplicateRequestDoesNotWrite()
            throws Exception {
        var first = input(today.plusDays(1), today.plusDays(2));
        var second = input(today.plusDays(1), today.plusDays(2));
        var quote = bookings.preview(first);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        var results = new ArrayList<Future<Long>>();
        try {
            for (var input : List.of(first, second))
                results.add(
                        pool.submit(
                                () -> {
                                    authenticate();
                                    try {
                                        gate.await(10, TimeUnit.SECONDS);
                                        return bookings.create(input, quote.totalAmount());
                                    } catch (AppException conflict) {
                                        assertThat(conflict.getMessage())
                                                .containsAnyOf("continuous", "liên tục");
                                        return -1L;
                                    } finally {
                                        reset();
                                    }
                                }));
            gate.countDown();
            var ids =
                    List.of(
                            results.get(0).get(30, TimeUnit.SECONDS),
                            results.get(1).get(30, TimeUnit.SECONDS));
            assertThat(ids.stream().filter(x -> x > 0)).hasSize(1);
            var winner = ids.get(0) > 0 ? first : second;
            assertThat(bookings.create(winner, quote.totalAmount()))
                    .isEqualTo(Math.max(ids.get(0), ids.get(1)));
            assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isEqualTo(1);
            assertThat(db.count("SELECT COUNT(*) FROM customers")).isEqualTo(1);
            assertThat(queries.reconcile(today, today.plusDays(3))).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dateChangeAndUpgradeRetainOriginalNightsAndLedgerHistory() {
        long booking = create(today.plusDays(2), today.plusDays(4));
        long line = line(booking);
        bookings.confirm(booking, "Confirmed");
        long upgrade = room("201", deluxe);
        long change =
                changes.request(
                        booking,
                        line,
                        new BookingChangeService.Change("CHANGE_ROOM", null, null, upgrade),
                        "Upgrade");
        changes.decide(booking, change, true, "Approved");
        changes.decide(booking, change, true, "Retried");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE booking_room_id=?"
                                    + " AND inventory_state='RELEASED'",
                                line))
                .isEqualTo(2);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE booking_room_id=?"
                                    + " AND revision=2",
                                line))
                .isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims WHERE room_id=?", upgrade))
                .isEqualTo(2);
        assertThat(ledger.balance(booking).total()).isEqualByComparingTo("2640000");
        long dates =
                changes.request(
                        booking,
                        line,
                        new BookingChangeService.Change(
                                "CHANGE_DATES", today.plusDays(3), today.plusDays(5), null),
                        "Move one day");
        changes.decide(booking, dates, true, "Applied");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE booking_room_id=?"
                                    + " AND stay_date=?",
                                line,
                                today.plusDays(3)))
                .isEqualTo(2);
        assertThat(ledger.balance(booking).total()).isEqualByComparingTo("2640000");
        assertThat(queries.reconcile(today, today.plusDays(7))).isEmpty();
    }

    @Test
    void cancellationKeepsFeeAndOnlyRefundsAgainstSuccessfulOriginal() {
        db.update(
                """
                UPDATE cancellation_policy_rules SET fee_type='PERCENT',fee_value=25
                WHERE cancellation_policy_id=(SELECT default_cancellation_policy_id FROM hotel_settings WHERE id=1)
                AND min_hours_before_checkin=0
                """);
        try {
            long booking = create(today.plusDays(1), today.plusDays(2));
            bookings.confirm(booking, "Confirmed");
            String payKey = code("");
            long paid =
                    payments.collect(
                            booking,
                            payKey,
                            "PAYMENT",
                            "BANK_TRANSFER",
                            new BigDecimal("880000"),
                            null);
            assertThat(
                            payments.collect(
                                    booking,
                                    payKey,
                                    "PAYMENT",
                                    "BANK_TRANSFER",
                                    new BigDecimal("880000"),
                                    null))
                    .isEqualTo(paid);
            bookings.cancel(booking, null, "Guest cancelled");
            bookings.cancel(booking, null, "Retry");
            assertThat(db.count("SELECT COUNT(*) FROM booking_cancellations")).isEqualTo(1);
            assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isZero();
            assertThat(ledger.balance(booking).total()).isEqualByComparingTo("200000");
            assertThatThrownBy(
                            () ->
                                    payments.refund(
                                            booking,
                                            code(""),
                                            paid,
                                            "BANK_TRANSFER",
                                            new BigDecimal("680001"),
                                            "Too much"))
                    .isInstanceOf(AppException.class);
            long refund =
                    payments.refund(
                            booking,
                            code(""),
                            paid,
                            "BANK_TRANSFER",
                            new BigDecimal("680000"),
                            "Approved");
            assertThat(refund).isPositive();
            assertThat(ledger.balance(booking).debt()).isEqualByComparingTo("0");
        } finally {
            db.update(
                    """
                    UPDATE cancellation_policy_rules SET fee_type='NONE',fee_value=0
                    WHERE cancellation_policy_id=(SELECT default_cancellation_policy_id FROM hotel_settings WHERE id=1)
                    AND min_hours_before_checkin=0
                    """);
        }
    }

    @Test
    void holdExpiryReleasesInventoryAndNeverDuplicatesCancellation() {
        long booking = create(today.plusDays(2), today.plusDays(3));
        db.update(
                "UPDATE bookings SET hold_expires_at=? WHERE id=?",
                OperationsStore.ts(Instant.now().minusSeconds(10)),
                booking);
        bookings.expire(booking);
        bookings.expire(booking);
        assertThat(db.one("SELECT status FROM bookings WHERE id=?", booking).s("status"))
                .isEqualTo("CANCELLED");
        assertThat(db.count("SELECT COUNT(*) FROM booking_cancellations WHERE kind='EXPIRED'"))
                .isEqualTo(1);
        assertThat(ledger.balance(booking).total()).isEqualByComparingTo("0");
        assertThat(queries.reconcile(today, today.plusDays(4))).isEmpty();
    }

    @Test
    void stayServiceCashAndInvoicePdfEndToEnd() throws Exception {
        long booking = create(today, today.plusDays(2));
        long line = line(booking);
        bookings.confirm(booking, "Confirmed");
        stays.checkIn(
                booking,
                line,
                List.of(new StayService.Guest("Nguyễn Văn Thắng", "ADULT", "ID", "000000000001")),
                "Documents verified");
        assertThat(
                        db.one("SELECT document_number_encrypted FROM booking_guests")
                                .s("document_number_encrypted"))
                .startsWith("v1:");
        long category = catalogue.saveCategory(null, "LAUNDRY", "Giặt là", true);
        long service =
                catalogue.save(
                        null,
                        new HotelServiceCatalogue.Item(
                                category,
                                "WASH",
                                "Giặt quần áo",
                                null,
                                "kg",
                                new BigDecimal("40000"),
                                new BigDecimal("10"),
                                true));
        String key = code("");
        long usage = catalogue.use(booking, line, service, new BigDecimal("1.250"), key, null);
        assertThat(catalogue.use(booking, line, service, new BigDecimal("1.250"), key, null))
                .isEqualTo(usage);
        long unused = catalogue.use(booking, line, service, BigDecimal.ONE, code(""), null);
        catalogue.voidUsage(booking, unused, "Wrong entry");
        assertThatThrownBy(() -> stays.checkOut(booking, line, "Early checkout"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () ->
                                payments.collect(
                                        booking,
                                        code(""),
                                        "PAYMENT",
                                        "CASH",
                                        new BigDecimal("935000"),
                                        null))
                .isInstanceOf(AppException.class);
        db.update(
                "INSERT INTO employees(user_id,employee_code) SELECT ?,? WHERE NOT EXISTS(SELECT 1"
                    + " FROM employees WHERE user_id=?)",
                user,
                "TEST-OPS",
                user);
        payments.open(BigDecimal.ZERO);
        payments.collect(booking, code(""), "PAYMENT", "CASH", new BigDecimal("935000"), null);
        stays.checkOut(booking, line, "Early checkout");
        assertThat(ledger.balance(booking).debt()).isEqualByComparingTo("0");
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("DIRTY");
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks WHERE room_id=?", room))
                .isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM booking_room_nights WHERE stay_status='STAYED'"))
                .isEqualTo(1);
        assertThat(queries.reconcile(today, today.plusDays(4))).isEmpty();
        long invoice = invoices.issue(booking, "Đường Trần Hưng Đạo", null);
        assertThat(invoices.issue(booking, null, null)).isEqualTo(invoice);
        assertThatThrownBy(() -> catalogue.voidUsage(booking, usage, "Locked"))
                .isInstanceOf(AppException.class);
        try (var document = PDDocument.load(invoices.download(invoice).data())) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Hóa đơn nội bộ", "Nguyễn Văn Thắng", "Giặt quần áo");
        }
        mvc.perform(get("/admin/invoices/{id}/pdf", invoice).session(admin))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/admin/invoices/{id}", invoice).session(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/bookings/{id}", booking).session(admin)).andExpect(status().isOk());
        authenticate();
        invoices.voidInvoice(invoice, "Correct service details");
        catalogue.voidUsage(booking, usage, "Removed after correction");
        long revision = invoices.issue(booking, null, null);
        var next = db.one("SELECT * FROM invoices WHERE id=?", revision);
        assertThat(next.id("supersedes_invoice_id")).isEqualTo(invoice);
        assertThat(next.money("total_amount")).isEqualByComparingTo("880000");
    }

    @Test
    void continuousAvailabilityRejectsFragmentedInventoryEvenWhenEachNightHasCapacity() {
        long second = room("102", type);
        for (var block :
                List.of(Map.entry(room, today.plusDays(1)), Map.entry(second, today.plusDays(2)))) {
            long blockId =
                    db.insert(
                            "room_blocks",
                            "room_id,block_type,from_date,to_date,reason,created_by",
                            block.getKey(),
                            "MAINTENANCE",
                            block.getValue(),
                            block.getValue().plusDays(1),
                            "Scheduled maintenance",
                            user);
            db.update(
                    "INSERT INTO room_night_claims(room_id,stay_date,room_block_id) VALUES(?,?,?)",
                    block.getKey(),
                    block.getValue(),
                    blockId);
        }
        assertThatThrownBy(() -> create(today.plusDays(1), today.plusDays(3)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("#");
        assertThat(db.count("SELECT COUNT(*) FROM bookings")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM customers")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isEqualTo(2);
    }

    @Test
    void movingAnOccupiedRoomClosesGuestSegmentAndTransfersCurrentNight() {
        long booking = create(today, today.plusDays(2));
        long line = line(booking);
        bookings.confirm(booking, "Confirmed");
        stays.checkIn(
                booking,
                line,
                List.of(new StayService.Guest("Guest", "ADULT", "ID", "000000000002")),
                "Verified");
        long other = room("102", type);
        long change =
                changes.request(
                        booking,
                        line,
                        new BookingChangeService.Change("CHANGE_ROOM", null, null, other),
                        "Move");
        changes.decide(booking, change, true, "Approved");
        assertThat(db.count("SELECT COUNT(*) FROM stay_guests WHERE booking_id=?", booking))
                .isEqualTo(2);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM stay_guests WHERE booking_id=? AND"
                                    + " departed_at IS NULL",
                                booking))
                .isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims WHERE room_id=?", other))
                .isEqualTo(2);
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("DIRTY");
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", other).s("status"))
                .isEqualTo("OCCUPIED");
        assertThat(ledger.balance(booking).total()).isEqualByComparingTo("1760000");
        assertThat(queries.reconcile(today, today.plusDays(3))).isEmpty();
    }

    @Test
    void formsRoutesAndRbac() throws Exception {
        for (String path :
                List.of(
                        "/admin/bookings",
                        "/admin/bookings/create",
                        "/admin/bookings/calendar",
                        "/admin/cancellations",
                        "/admin/services",
                        "/admin/service-categories",
                        "/admin/payments",
                        "/admin/debts",
                        "/admin/invoices",
                        "/admin/inventory/reconcile")) {
            mvc.perform(get(path).session(admin)).andExpect(status().isOk());
        }
        var quote =
                post("/admin/bookings/create")
                        .session(admin)
                        .with(csrf())
                        .param("requestKey", code(""))
                        .param("source", "PHONE")
                        .param("name", "Test Guest")
                        .param("phone", "0912345678")
                        .param("typeId", Long.toString(type))
                        .param("from", today.toString())
                        .param("until", today.plusDays(1).toString())
                        .param("adults", "1")
                        .param("children", "0");
        mvc.perform(quote).andExpect(status().isOk()).andExpect(model().attributeExists("quote"));
        mvc.perform(quote.param("expected", "880000")).andExpect(status().is3xxRedirection());
        long booking = db.one("SELECT id FROM bookings").id("id");
        mvc.perform(get("/admin/bookings/{id}", booking).session(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/bookings/calendar").param("view", "month").session(admin))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/admin/bookings/{id}/confirm", booking)
                                .session(admin)
                                .param("reason", "No CSRF"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/bookings/{id}/confirm", booking)
                                .session(admin)
                                .with(csrf())
                                .param("reason", "OK"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/bookings/{id}", booking).session(admin)).andExpect(status().isOk());
        user("RECEPTIONIST");
        var receptionist = login("RECEPTIONIST");
        mvc.perform(get("/admin/bookings").session(receptionist)).andExpect(status().isOk());
        mvc.perform(get("/admin/inventory/reconcile").session(receptionist))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/bookings/{id}/refund", booking)
                                .session(receptionist)
                                .with(csrf())
                                .param("key", code(""))
                                .param("parent", "1")
                                .param("method", "BANK_TRANSFER")
                                .param("amount", "1")
                                .param("reason", "No permission"))
                .andExpect(status().isForbidden());
    }
}
