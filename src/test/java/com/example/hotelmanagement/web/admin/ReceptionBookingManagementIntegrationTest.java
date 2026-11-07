package com.example.hotelmanagement.web.admin;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.booking.BookingService.*;
import com.example.hotelmanagement.modules.customer.service.IdentityDocumentCrypto;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.payment.*;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.support.CmsTestData;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceptionBookingManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperationsStore db;
    @Autowired UserService users;
    @Autowired BookingService bookings;
    @Autowired BookingDeskOperations desk;
    @Autowired BookingAvailabilityService availability;
    @Autowired BookingChangeService changes;
    @Autowired BookingLedger ledger;
    @Autowired FrontDeskPaymentService payments;
    @Autowired StayService stays;
    @Autowired IdentityDocumentCrypto crypto;
    @Autowired HotelInventoryCoordinator inventory;
    @Autowired PlatformTransactionManager tx;
    MockHttpSession session;
    long user, type, deluxe, floor, room;
    LocalDate today;

    @BeforeEach
    void setup() throws Exception {
        CmsTestData.clear(jdbc);
        user =
                users.createUser(
                                CreateUserRequest.builder()
                                        .fullName("Booking Receptionist")
                                        .email("booking.desk@example.test")
                                        .password("InitialPass123")
                                        .roles(Set.of("RECEPTIONIST"))
                                        .build())
                        .getId();
        session =
                (MockHttpSession)
                        mvc.perform(
                                        post("/admin/login")
                                                .with(csrf())
                                                .param("email", "booking.desk@example.test")
                                                .param("password", "InitialPass123"))
                                .andExpect(status().is3xxRedirection())
                                .andReturn()
                                .getRequest()
                                .getSession(false);
        authenticate();
        today = db.today();
        type = db.one("SELECT id FROM room_types WHERE code='STD'").id("id");
        deluxe = db.one("SELECT id FROM room_types WHERE code='DLX'").id("id");
        db.update("UPDATE room_types SET base_price=800000,is_active=TRUE WHERE id=?", type);
        db.update("UPDATE room_types SET base_price=1200000,is_active=TRUE WHERE id=?", deluxe);
        db.update("UPDATE hotel_settings SET default_tax_percent=10 WHERE id=1");
        floor =
                db.insert(
                        "floors", "code,name,area_name,floor_number", "DESK_F1", "Tầng 1", "A", 1);
        room = room("101", type);
    }

    @AfterEach
    void reset() {
        SecurityContextHolder.clearContext();
    }

    void authenticate() {
        SecurityContextHolder.setContext(
                (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT"));
    }

    long room(String number, long type) {
        long id =
                db.insert(
                        "rooms",
                        "room_number,room_type_id,floor_id,sell_from",
                        number,
                        type,
                        floor,
                        db.today());
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        status -> {
                            inventory.lock();
                            inventory.reconcile(List.of(type));
                        });
        return id;
    }

    Create input(String source, LocalDate from, LocalDate until, int count) {
        return new Create(
                code(""),
                null,
                "Nguyễn Minh",
                "090 123-4567",
                "guest@example.test",
                source,
                null,
                null,
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> new RoomInput(type, from, until, 1, 0))
                        .toList());
    }

    long create(String source, LocalDate from, LocalDate until, int count) {
        var input = input(source, from, until, count);
        return bookings.create(input, bookings.preview(input).totalAmount());
    }

    long line(long booking) {
        return db.one("SELECT id FROM booking_rooms WHERE booking_id=? ORDER BY id", booking)
                .id("id");
    }

    @Test
    void availabilityDistinguishesFragmentedDatesFromContinuousRoomsAndNeverWrites()
            throws Exception {
        long second = room("102", type);
        long first = create("PHONE", today.plusDays(1), today.plusDays(2), 1);
        long other = create("PHONE", today.plusDays(2), today.plusDays(3), 1);
        desk.assign(other, line(other), second, "Separate night");
        var result =
                availability
                        .check(today.plusDays(1), today.plusDays(3), 1, 0, 1, type, false)
                        .options()
                        .get(0);
        assertThat(result.nights()).allSatisfy(n -> assertThat(n.remaining()).isEqualTo(1));
        assertThat(result.continuous()).isZero();
        assertThat(result.canBook()).isFalse();
        assertThat(result.total()).isEqualByComparingTo("1760000");
        long outbox = db.count("SELECT COUNT(*) FROM outbox_events");
        mvc.perform(
                        get("/admin/bookings/availability")
                                .session(session)
                                .param("from", today.plusDays(1).toString())
                                .param("until", today.plusDays(3).toString())
                                .param("typeId", Long.toString(type)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].canBook").value(false));
        assertThat(db.count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(outbox);
        assertThat(db.count("SELECT COUNT(*) FROM bookings")).isEqualTo(2);
    }

    @Test
    void availabilityHandlesMissingInventoryAndImmediateReadinessWithoutInventingCapacity() {
        db.update("DELETE FROM room_type_inventory WHERE room_type_id=?", type);
        db.update("UPDATE rooms SET status='DIRTY' WHERE id=?", room);
        var normal =
                availability.check(today, today.plusDays(1), 1, 0, 1, type, false).options().get(0);
        assertThat(normal.available()).isEqualTo(1);
        assertThat(normal.nights().get(0).estimated()).isTrue();
        assertThat(
                        availability
                                .check(today, today.plusDays(1), 1, 0, 1, type, true)
                                .options()
                                .get(0)
                                .canBook())
                .isFalse();
        assertThat(db.count("SELECT COUNT(*) FROM room_type_inventory WHERE room_type_id=?", type))
                .isZero();
        assertThat(availability.check(today, today.plusDays(1), 99, 0, 1, type, false).options())
                .isEmpty();
    }

    @Test
    void assignmentKeepsNightsMoneyAndCountersAndPreservesAllocationHistory() {
        long second = room("102", type);
        long booking = create("PHONE", today.plusDays(1), today.plusDays(3), 1);
        long line = line(booking);
        var before = ledger.balance(booking).total();
        desk.assign(booking, line, second, "Guest preference");
        desk.assign(booking, line, second, "Retry");
        assertThat(db.count("SELECT COUNT(*) FROM room_allocations WHERE booking_room_id=?", line))
                .isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims WHERE room_id=?", second))
                .isEqualTo(2);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE booking_room_id=?",
                                line))
                .isEqualTo(2);
        assertThat(ledger.balance(booking).total()).isEqualByComparingTo(before);
        assertThat(
                        db.count(
                                "SELECT SUM(held_rooms) FROM room_type_inventory WHERE"
                                        + " room_type_id=?",
                                type))
                .isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='BOOKING_ROOM_REASSIGN'"))
                .isEqualTo(1);
        bookings.confirm(booking, "Verified");
        assertThat(
                        db.count(
                                "SELECT SUM(reserved_rooms) FROM room_type_inventory WHERE"
                                        + " room_type_id=?",
                                type))
                .isEqualTo(2);
    }

    @Test
    void assignedRoomConflictAndBrokenClaimsRollbackWithoutLosingOriginalReservation() {
        long second = room("102", type);
        long a = create("PHONE", today.plusDays(1), today.plusDays(3), 1);
        long b = create("PHONE", today.plusDays(1), today.plusDays(3), 1);
        assertThatThrownBy(() -> desk.assign(a, line(a), second, "Conflict"))
                .isInstanceOf(AppException.class);
        db.update(
                "DELETE FROM room_night_claims WHERE room_allocation_id=(SELECT id FROM"
                        + " room_allocations WHERE booking_room_id=?)",
                line(a));
        assertThatThrownBy(() -> bookings.confirm(a, "Broken claim"))
                .isInstanceOf(AppException.class);
        assertThat(db.one("SELECT status FROM bookings WHERE id=?", a).s("status"))
                .isEqualTo("PENDING");
        assertThat(
                        db.count(
                                "SELECT SUM(held_rooms) FROM room_type_inventory WHERE"
                                        + " room_type_id=?",
                                type))
                .isEqualTo(4);
    }

    @Test
    void multiRoomPrefillQuoteAndCustomerDocumentLookupRespectCsrfAndMasking() throws Exception {
        room("102", type);
        mvc.perform(
                        get("/admin/bookings/create")
                                .session(session)
                                .param("typeId", Long.toString(type))
                                .param("quantity", "2")
                                .param("from", today.toString())
                                .param("until", today.plusDays(2).toString()))
                .andExpect(status().isOk())
                .andExpect(
                        result ->
                                assertThat(
                                                ((Create)
                                                                result.getModelAndView()
                                                                        .getModel()
                                                                        .get("f"))
                                                        .rooms())
                                        .hasSize(2));
        mvc.perform(
                        post("/admin/bookings/quote")
                                .session(session)
                                .with(csrf())
                                .param("typeId", Long.toString(type), Long.toString(type))
                                .param("from", today.toString(), today.toString())
                                .param(
                                        "until",
                                        today.plusDays(1).toString(),
                                        today.plusDays(1).toString())
                                .param("adults", "1", "1")
                                .param("children", "0", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomCount").value(2));
        authenticate();
        long customer =
                db.insert(
                        "customers",
                        "customer_code,full_name,phone,email,document_type,document_number_hash,document_number_encrypted",
                        code("C"),
                        "Nguyễn Mai",
                        "090 123-4567",
                        "mai@example.test",
                        "ID",
                        crypto.hash("000000000123"),
                        crypto.encrypt("000000000123"));
        mvc.perform(
                        post("/admin/bookings/customers/search")
                                .session(session)
                                .with(csrf())
                                .param("q", "0901234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(customer));
        var response =
                mvc.perform(
                                post("/admin/bookings/customers/search")
                                        .session(session)
                                        .with(csrf())
                                        .param("mode", "DOCUMENT")
                                        .param("q", "000000000123"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].name").value("Nguyễn Mai"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).doesNotContain("000000000123").contains("0123");
        mvc.perform(
                        post("/admin/bookings/customers/search")
                                .session(session)
                                .param("q", "0901234567"))
                .andExpect(status().isForbidden());
    }

    @Test
    void walkInCollectionConfirmationAndCheckinAreOneTransactionAndRetryDoesNotCollectTwice()
            throws Exception {
        long booking = create("WALK_IN", today, today.plusDays(1), 1), line = line(booking);
        var guests = List.of(new StayService.Guest("Nguyễn Minh", "ADULT", "ID", "000000000123"));
        db.update("UPDATE rooms SET status='DIRTY' WHERE id=?", room);
        String key = code("");
        assertThatThrownBy(
                        () ->
                                desk.walkIn(
                                        booking,
                                        line,
                                        guests,
                                        "Walk-in",
                                        key,
                                        "BANK_TRANSFER",
                                        new BigDecimal("880000")))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
        assertThat(db.one("SELECT status FROM bookings WHERE id=?", booking).s("status"))
                .isEqualTo("PENDING");
        db.update("UPDATE rooms SET status='AVAILABLE' WHERE id=?", room);
        mvc.perform(
                        get("/admin/bookings/{id}/rooms/{line}/walk-in-preview", booking, line)
                                .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.status").value("PENDING"));
        authenticate();
        desk.walkIn(
                booking, line, guests, "Walk-in", key, "BANK_TRANSFER", new BigDecimal("880000"));
        desk.walkIn(booking, line, guests, "Retry", key, "BANK_TRANSFER", new BigDecimal("880000"));
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(1);
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("OCCUPIED");
    }

    @Test
    void extensionCanMoveToAnotherContinuousRoomAndOnlyPricesTheAddedNight() {
        long second = room("102", type);
        long booking = create("WALK_IN", today, today.plusDays(1), 1), line = line(booking);
        desk.walkIn(
                booking,
                line,
                List.of(new StayService.Guest("Guest", "ADULT", "ID", "000000000123")),
                "Arrived",
                code(""),
                "BANK_TRANSFER",
                BigDecimal.ZERO);
        long next = create("PHONE", today.plusDays(1), today.plusDays(2), 1);
        desk.assign(next, line(next), room, "Reserve the currently occupied room for tomorrow");
        assertThatThrownBy(
                        () ->
                                changes.preview(
                                        booking,
                                        line,
                                        new BookingChangeService.Change(
                                                "EXTEND", null, today.plusDays(2), null)))
                .isInstanceOf(AppException.class);
        var change = new BookingChangeService.Change("EXTEND", null, today.plusDays(2), second);
        var preview = changes.preview(booking, line, change);
        assertThat(preview.difference()).isEqualByComparingTo("880000");
        long request =
                changes.requestReviewed(
                        booking,
                        line,
                        change,
                        "Extend in another room",
                        preview.bookingVersion(),
                        preview.difference());
        changes.decide(booking, request, true, "Approved");
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims WHERE room_id=?", second))
                .isEqualTo(2);
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("DIRTY");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM stay_guests WHERE booking_id=? AND"
                                        + " departed_at IS NULL",
                                booking))
                .isEqualTo(1);
        assertThat(ledger.balance(booking).total()).isEqualByComparingTo("1760000");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE booking_room_id=?",
                                line))
                .isEqualTo(2);
    }

    @Test
    void shortenedStaySnapshotsPolicyFeeCreditsAndRejectsStaleReview() {
        var policy =
                db.one("SELECT default_cancellation_policy_id FROM hotel_settings WHERE id=1")
                        .id("default_cancellation_policy_id");
        db.update(
                "UPDATE cancellation_policy_rules SET fee_type='PERCENT',fee_value=25 WHERE"
                        + " cancellation_policy_id=? AND min_hours_before_checkin=0",
                policy);
        try {
            long booking = create("PHONE", today.plusDays(1), today.plusDays(4), 1),
                    line = line(booking);
            bookings.confirm(booking, "OK");
            payments.collect(
                    booking, code(""), "PAYMENT", "BANK_TRANSFER", new BigDecimal("2640000"), null);
            var change = new BookingChangeService.Change("SHORTEN", null, today.plusDays(2), null);
            var review = changes.preview(booking, line, change);
            assertThat(review.fee()).isEqualByComparingTo("400000");
            assertThat(review.refundDue()).isEqualByComparingTo("1360000");
            assertThatThrownBy(
                            () ->
                                    changes.requestReviewed(
                                            booking,
                                            line,
                                            change,
                                            "Stale",
                                            review.bookingVersion() + 1,
                                            review.difference()))
                    .isInstanceOf(AppException.class);
            long request =
                    changes.requestReviewed(
                            booking,
                            line,
                            change,
                            "Leave early",
                            review.bookingVersion(),
                            review.difference());
            changes.decide(booking, request, true, "Approved");
            assertThat(
                            db.count(
                                    "SELECT COUNT(*) FROM booking_room_nights WHERE"
                                            + " inventory_state='RELEASED'"))
                    .isEqualTo(2);
            assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isEqualTo(1);
            assertThat(ledger.balance(booking).refundDue()).isEqualByComparingTo("1360000");
        } finally {
            db.update(
                    "UPDATE cancellation_policy_rules SET fee_type='NONE',fee_value=0 WHERE"
                            + " cancellation_policy_id=? AND min_hours_before_checkin=0",
                    policy);
        }
    }

    @Test
    void noShowOnlyReleasesSelectedRoomAndUsesAcceptedFirstNightFee() throws Exception {
        var policy =
                db.one("SELECT default_cancellation_policy_id FROM hotel_settings WHERE id=1")
                        .id("default_cancellation_policy_id");
        db.update(
                "UPDATE cancellation_policies SET"
                        + " no_show_fee_type='FIRST_NIGHT',no_show_fee_value=0 WHERE id=?",
                policy);
        try {
            room("102", type);
            long booking = create("PHONE", today, today.plusDays(2), 2), line = line(booking);
            bookings.confirm(booking, "OK");
            db.update(
                    "UPDATE booking_rooms SET expected_check_in_at=? WHERE id=?",
                    ts(Instant.now().minusSeconds(60)),
                    line);
            mvc.perform(
                            get("/admin/bookings/{id}/rooms/{line}/no-show-preview", booking, line)
                                    .session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fee").value(800000));
            authenticate();
            bookings.noShow(booking, line, "No contact");
            bookings.noShow(booking, line, "Retry");
            assertThat(db.count("SELECT COUNT(*) FROM booking_cancellations")).isEqualTo(1);
            assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isEqualTo(2);
            assertThat(db.one("SELECT status FROM bookings WHERE id=?", booking).s("status"))
                    .isEqualTo("CONFIRMED");
            assertThat(ledger.balance(booking).total()).isEqualByComparingTo("2560000");
        } finally {
            db.update(
                    "UPDATE cancellation_policies SET no_show_fee_type='NONE',no_show_fee_value=0"
                            + " WHERE id=?",
                    policy);
        }
    }

    @Test
    void listFiltersCombineLineConditionsAndShowMoneyAndAssignedRooms() throws Exception {
        long booking = create("PHONE", today, today.plusDays(1), 1);
        bookings.confirm(booking, "OK");
        mvc.perform(
                        get("/admin/bookings")
                                .session(session)
                                .param("quick", "ARRIVALS")
                                .param("q", "0901234567"))
                .andExpect(status().isOk())
                .andExpect(
                        result ->
                                assertThat(
                                                ((OperationsQueries.Page)
                                                                result.getModelAndView()
                                                                        .getModel()
                                                                        .get("page"))
                                                        .total())
                                        .isEqualTo(1))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("880,000")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("101")));
        mvc.perform(get("/admin/bookings/{id}", booking).session(session))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "booking-room-dialog")));
        mvc.perform(
                        get("/admin/bookings/availability")
                                .session(session)
                                .param("from", today.toString())
                                .param("until", today.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/admin/bookings/{id}/assign-room", booking)
                                .session(session)
                                .param("line", Long.toString(line(booking)))
                                .param("room", Long.toString(room))
                                .param("reason", "No CSRF"))
                .andExpect(status().isForbidden());
    }
}
