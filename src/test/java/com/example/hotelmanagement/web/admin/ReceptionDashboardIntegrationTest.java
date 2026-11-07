package com.example.hotelmanagement.web.admin;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.booking.BookingService.*;
import com.example.hotelmanagement.modules.payment.*;
import com.example.hotelmanagement.modules.reception.*;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.support.CmsTestData;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceptionDashboardIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired OperationsStore db;
    @Autowired BookingService bookings;
    @Autowired StayService stays;
    @Autowired ReceptionDashboardService dashboard;
    @Autowired FrontDeskPaymentService payments;
    @Autowired ReceptionActionService actions;
    @Autowired ReceptionOutboxDispatcher dispatcher;
    @Autowired ReceptionNotificationService notifications;
    @Autowired ReceptionNotificationPublisher publisher;
    @Autowired PlatformTransactionManager transactions;

    @Autowired
    com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator inventory;

    MockHttpSession desk, admin;
    Authentication principal;
    long user, adminId, type, room;
    LocalDate today;

    @BeforeEach
    void setup() throws Exception {
        CmsTestData.clear(jdbc);
        adminId = createUser("ADMIN");
        user = createUser("RECEPTIONIST");
        admin = login("ADMIN");
        desk = login("RECEPTIONIST");
        principal =
                ((SecurityContext) desk.getAttribute("SPRING_SECURITY_CONTEXT"))
                        .getAuthentication();
        authenticate();
        today = db.today();
        type = db.one("SELECT id FROM room_types WHERE code='STD'").id("id");
        db.update("UPDATE room_types SET base_price=800000,is_active=TRUE WHERE id=?", type);
        db.update("UPDATE hotel_settings SET default_tax_percent=10 WHERE id=1");
        long floor =
                db.insert("floors", "code,name,area_name,floor_number", "DESK1", "Tầng 1", "A", 1);
        room =
                db.insert(
                        "rooms",
                        "room_number,room_type_id,floor_id,sell_from",
                        "101",
                        type,
                        floor,
                        today);
    }

    @AfterEach
    void finish() {
        SecurityContextHolder.clearContext();
    }

    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(principal);
    }

    long createUser(String role) {
        return users.createUser(
                        CreateUserRequest.builder()
                                .fullName("Desk " + role)
                                .email(role.toLowerCase() + "@desk.example.test")
                                .password("InitialPass123")
                                .roles(Set.of(role))
                                .build())
                .getId();
    }

    MockHttpSession login(String role) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/admin/login")
                                        .with(csrf())
                                        .param("email", role.toLowerCase() + "@desk.example.test")
                                        .param("password", "InitialPass123"))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(
                                redirectedUrl(
                                        role.equals("ADMIN") ? "/admin" : "/admin/receptionist"))
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    long create() {
        var f =
                new Create(
                        code(""),
                        null,
                        "Nguyễn Mai",
                        "0912345678",
                        null,
                        "PHONE",
                        null,
                        null,
                        List.of(new RoomInput(type, today, today.plusDays(1), 1, 0)));
        return bookings.create(f, bookings.preview(f).totalAmount());
    }

    long line(long booking) {
        return db.one("SELECT id FROM booking_rooms WHERE booking_id=?", booking).id("id");
    }

    @Test
    void dashboardRendersQueuesAccordionAndEnforcesPermissions() throws Exception {
        long booking = create();
        var dashboardResponse =
                mvc.perform(get("/admin/receptionist").session(desk))
                        .andExpect(status().isOk())
                        .andExpect(
                                content()
                                        .string(
                                                org.hamcrest.Matchers.containsString(
                                                        "data-nav-module=\"operations\"")))
                        .andExpect(
                                content()
                                        .string(org.hamcrest.Matchers.containsString("Nguyễn Mai")))
                        .andReturn();
        var openModules =
                java.util.regex.Pattern.compile(
                                "<details[^>]*class=\"cms-nav-group\"[^>]*open=\"open\"")
                        .matcher(dashboardResponse.getResponse().getContentAsString())
                        .results()
                        .count();
        assertThat(openModules).isEqualTo(1);
        mvc.perform(get("/admin/receptionist/fragment").session(desk)).andExpect(status().isOk());
        mvc.perform(get("/admin/api/receptionist/dashboard").session(desk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.arrivals").value(1))
                .andExpect(jsonPath("$.kpis.ready").value(1))
                .andExpect(jsonPath("$.kpis.pending").value(1));
        mvc.perform(get("/admin/receptionist").session(admin)).andExpect(status().isOk());
        mvc.perform(
                        post("/admin/receptionist/quick-confirm/{id}", booking)
                                .session(desk)
                                .param("reason", "No CSRF"))
                .andExpect(status().isForbidden());
        createUser("USER");
        var member =
                (MockHttpSession)
                        mvc.perform(
                                        post("/admin/login")
                                                .with(csrf())
                                                .param("email", "user@desk.example.test")
                                                .param("password", "InitialPass123"))
                                .andReturn()
                                .getRequest()
                                .getSession(false);
        mvc.perform(get("/admin/api/receptionist/dashboard"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void shiftCashAndRequiredVarianceNotesUseExistingCollections() throws Exception {
        long booking = create();
        payments.open(new BigDecimal("100000"), "Bàn giao đầu ca");
        payments.collect(booking, code(""), "PAYMENT", "CASH", new BigDecimal("500000"), null);
        payments.collect(
                booking, code(""), "PAYMENT", "BANK_TRANSFER", new BigDecimal("100000"), null);
        var shift = dashboard.snapshot().shift();
        assertThat(shift.collected()).isEqualByComparingTo("500000");
        assertThat(shift.expected()).isEqualByComparingTo("600000");
        assertThatThrownBy(() -> payments.close(new BigDecimal("599000"), null))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM work_sessions WHERE closed_at IS NULL"))
                .isEqualTo(1);
        payments.close(new BigDecimal("599000"), "Thiếu 1000 khi kiểm đếm");
        assertThat(db.one("SELECT notes FROM work_sessions").s("notes"))
                .contains("Bàn giao đầu ca", "Thiếu 1000");
        assertThat(dashboard.snapshot().shift().id()).isNull();
    }

    @Test
    void concurrentOpeningOnlyCreatesOneShift() throws Exception {
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    authenticate();
                                    try {
                                        gate.await(10, TimeUnit.SECONDS);
                                        payments.open(BigDecimal.ZERO, "Test");
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            gate.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
            assertThat(db.count("SELECT COUNT(*) FROM work_sessions WHERE closed_at IS NULL"))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void quickCheckinRollsBackCollectionWhenRoomIsDirtyThenCompletesStay() throws Exception {
        long booking = create(), line = line(booking);
        bookings.confirm(booking, "Verified");
        db.update("UPDATE rooms SET status='DIRTY' WHERE id=?", room);
        var request =
                post("/admin/receptionist/quick-checkin")
                        .session(desk)
                        .with(csrf())
                        .param("booking", Long.toString(booking))
                        .param("line", Long.toString(line))
                        .param("reason", "Verified documents")
                        .param("key", code(""))
                        .param("method", "BANK_TRANSFER")
                        .param("amount", "880000")
                        .param("guestName", "Nguyễn Mai")
                        .param("guestType", "ADULT")
                        .param("documentType", "ID")
                        .param("documentNumber", "000000000123");
        mvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
        db.update("UPDATE rooms SET status='AVAILABLE' WHERE id=?", room);
        mvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", org.hamcrest.Matchers.nullValue()));
        authenticate();
        assertThat(dashboard.snapshot().kpis().occupied()).isEqualTo(1);
        assertThat(dashboard.snapshot().kpis().ready()).isEqualTo(0);
        var quote = stays.previewCheckOut(booking, line);
        mvc.perform(
                        post("/admin/receptionist/quick-checkout")
                                .session(desk)
                                .with(csrf())
                                .param("booking", Long.toString(booking))
                                .param("line", Long.toString(line))
                                .param("reason", "Checked balance")
                                .param("key", code(""))
                                .param("amount", "0")
                                .param("expectedDebt", quote.projectedDebt().toPlainString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", org.hamcrest.Matchers.nullValue()));
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("DIRTY");
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks")).isEqualTo(1);
    }

    @Test
    void lateCheckoutQuoteCollectsSurchargeAtomicallyAndRejectsStaleAmount() throws Exception {
        long booking = create(), line = line(booking);
        bookings.confirm(booking, "OK");
        stays.checkIn(
                booking,
                line,
                List.of(new StayService.Guest("Guest", "ADULT", "ID", "000000000123")),
                "OK");
        payments.collect(
                booking, code(""), "PAYMENT", "BANK_TRANSFER", new BigDecimal("880000"), null);
        db.update(
                "UPDATE booking_rooms SET expected_check_in_at=?,expected_check_out_at=? WHERE"
                    + " id=?",
                ts(Instant.now().minusSeconds(7200)),
                ts(Instant.now().minusSeconds(3600)),
                line);
        db.insert(
                "surcharge_policies",
                "code,name,kind,amount_type,amount_value",
                "TEST_LATE",
                "Late checkout",
                "LATE_CHECKOUT",
                "FIXED",
                100000);
        var preview = stays.previewCheckOut(booking, line);
        assertThat(preview.projectedDebt()).isEqualByComparingTo("110000");
        mvc.perform(
                        get("/admin/api/receptionist/rooms/{line}/preview", line)
                                .session(desk)
                                .param("booking", Long.toString(booking))
                                .param("action", "checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.surcharge.total").value(110000));
        authenticate();
        assertThat(dashboard.snapshot().lateDepartures()).hasSize(1);
        assertThatThrownBy(
                        () ->
                                stays.checkOutAndCollect(
                                        booking,
                                        line,
                                        "Close",
                                        new StayService.Settlement(
                                                code(""),
                                                "BANK_TRANSFER",
                                                new BigDecimal("110000"),
                                                BigDecimal.ZERO)))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM booking_charges WHERE kind='SURCHARGE'"))
                .isZero();
        stays.checkOutAndCollect(
                booking,
                line,
                "Close",
                new StayService.Settlement(
                        code(""),
                        "BANK_TRANSFER",
                        new BigDecimal("110000"),
                        preview.projectedDebt()));
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(2);
        assertThat(db.one("SELECT status FROM booking_rooms WHERE id=?", line).s("status"))
                .isEqualTo("CHECKED_OUT");
    }

    @Test
    void outboxNotificationsAreDurableDeduplicatedAndReadOnlyForRecipient() throws Exception {
        long booking = create();
        dispatcher.dispatch();
        dispatcher.dispatch();
        var feed = notifications.feed();
        assertThat(feed.unread()).isEqualTo(1);
        assertThat(feed.items().get(0).type()).isEqualTo("BOOKING_CREATED");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM notification_deliveries WHERE"
                                        + " channel='IN_APP' AND status='SENT'"))
                .isEqualTo(2);
        long other = db.one("SELECT id FROM notifications WHERE user_id=?", adminId).id("id");
        notifications.read(List.of(other));
        assertThat(notifications.feed().unread()).isEqualTo(1);
        mvc.perform(
                        post("/admin/api/receptionist/notifications/read")
                                .session(desk)
                                .with(csrf())
                                .param("ids", Long.toString(feed.items().get(0).id())))
                .andExpect(status().isOk());
        authenticate();
        assertThat(notifications.feed().unread()).isZero();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.event("ROLLBACK_TEST", "BOOKING_CHANGED", booking);
                            status.setRollbackOnly();
                        });
        dispatcher.dispatch();
        assertThat(db.count("SELECT COUNT(*) FROM notifications WHERE user_id=?", user))
                .isEqualTo(1);
    }

    @Test
    void streamSendsRefreshAndClosesWhenSessionExpires() throws Exception {
        var stream =
                mvc.perform(get("/admin/api/receptionist/stream").session(desk))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:refresh");
        desk.invalidate();
        publisher.heartbeat();
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
    }

    @Test
    void alertsUseHotelTimezoneAndExcludeExpiredPendingFromConfirmationQueue() {
        long booking = create(), line = line(booking);
        bookings.confirm(booking, "OK");
        db.update(
                "UPDATE booking_rooms SET expected_check_in_at=? WHERE id=?",
                ts(Instant.now().minusSeconds(3 * 3600)),
                line);
        var snapshot = dashboard.snapshot();
        assertThat(snapshot.today()).isEqualTo(LocalDate.now(db.zone()));
        assertThat(snapshot.lateArrivals()).hasSize(1);
        long other = createOnOtherRoom();
        db.update(
                "UPDATE bookings SET hold_expires_at=? WHERE id=?",
                ts(Instant.now().minusSeconds(5)),
                other);
        assertThat(dashboard.snapshot().pending()).isEmpty();
    }

    private long createOnOtherRoom() {
        long floor = db.one("SELECT floor_id FROM rooms WHERE id=?", room).id("floor_id");
        db.insert(
                "rooms", "room_number,room_type_id,floor_id,sell_from", "102", type, floor, today);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            inventory.lock();
                            inventory.reconcile(List.of(type));
                        });
        return create();
    }
}
