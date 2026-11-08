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
class ReceptionRoomCareIntegrationTest {
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

    @Autowired com.example.hotelmanagement.modules.room.service.HousekeepingService cleaning;
    @Autowired com.example.hotelmanagement.modules.room.service.RoomMaintenanceService maintenance;
    @Autowired com.example.hotelmanagement.modules.booking.GuestRequestService requests;
    @Autowired com.example.hotelmanagement.modules.customer.service.CustomerStayHistory history;
    @Autowired com.example.hotelmanagement.modules.customer.service.CustomerService customers;
    @Autowired com.example.hotelmanagement.modules.reception.GuestMessageService messages;
    @Autowired com.example.hotelmanagement.modules.reception.GuestEmailWorker worker;
    @Autowired com.example.hotelmanagement.modules.reception.GuestEmailQueue mailQueue;
    @Autowired com.example.hotelmanagement.modules.reception.ReceptionBookingLookup lookup;
    @Autowired com.example.hotelmanagement.modules.reception.ReceptionOutboxDispatcher dispatcher;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.example.hotelmanagement.modules.reception.GuestEmailTransport transport;

    long occupied(int nights) {
        long b = create("PHONE", today, today.plusDays(nights), 1);
        bookings.confirm(b, "Confirmed for arrival");
        stays.checkIn(
                b,
                line(b),
                List.of(new StayService.Guest("Nguyễn Minh", "ADULT", "ID", "012345678901")),
                "Guest arrived");
        return b;
    }

    void finish(long booking) {
        payments.collect(
                booking,
                code(""),
                "PAYMENT",
                "BANK_TRANSFER",
                ledger.balance(booking).debt(),
                "Settlement");
        stays.checkOut(booking, line(booking), "Checkout completed");
    }

    String roomStatus() {
        return db.one("SELECT status FROM rooms WHERE id=?", room).s("status");
    }

    @Test
    void turnoverRequiresOrderedVerificationAndReusesOneActiveTask() {
        long task = cleaning.request(room, "Khách VIP đến lúc 14h", false);
        assertThat(roomStatus()).isEqualTo("DIRTY");
        assertThat(cleaning.request(room, "Ưu tiên dọn ngay", true)).isEqualTo(task);
        assertThat(db.one("SELECT * FROM housekeeping_tasks WHERE id=?", task).s("priority"))
                .isEqualTo("HIGH");
        assertThatThrownBy(() -> cleaning.clean(task, "verify", "Check clean room"))
                .isInstanceOf(AppException.class);
        cleaning.clean(task, "start", "Bắt đầu vệ sinh");
        assertThat(roomStatus()).isEqualTo("CLEANING");
        cleaning.clean(task, "complete", "Đã thay ga gối");
        assertThat(roomStatus()).isEqualTo("CLEANING");
        cleaning.clean(task, "verify", "Nghiệm thu đạt chuẩn");
        assertThat(roomStatus()).isEqualTo("AVAILABLE");
        assertThat(db.one("SELECT * FROM housekeeping_tasks WHERE id=?", task).id("verified_by"))
                .isEqualTo(user);
    }

    @Test
    void stayOverNeverMakesOccupiedRoomSellableAndCheckoutCancelsUnfinishedStayOver() {
        long b = occupied(2),
                task = cleaning.request(room, "Khách ra ngoài 14–16h, thay khăn", false);
        assertThat(db.one("SELECT * FROM housekeeping_tasks WHERE id=?", task).s("task_kind"))
                .isEqualTo("STAY_OVER");
        for (String action : List.of("start", "complete", "verify")) {
            cleaning.clean(task, action, "Phục vụ trong kỳ ở");
            assertThat(roomStatus()).isEqualTo("OCCUPIED");
        }
        long next = cleaning.request(room, "Dọn giữa kỳ lần hai", false);
        finish(b);
        assertThat(roomStatus()).isEqualTo("DIRTY");
        assertThat(db.one("SELECT status FROM housekeeping_tasks WHERE id=?", next).s("status"))
                .isEqualTo("CANCELLED");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM housekeeping_tasks WHERE room_id=? AND"
                                        + " task_kind='TURNOVER' AND status='PENDING'",
                                room))
                .isEqualTo(1);
        assertThatThrownBy(() -> cleaning.clean(next, "start", "Không được dùng việc cũ"))
                .isInstanceOf(AppException.class);
    }

    com.example.hotelmanagement.modules.room.service.RoomMaintenanceService.Fault fault(
            String key, boolean block, LocalDate from, LocalDate until) {
        return new com.example.hotelmanagement.modules.room.service.RoomMaintenanceService.Fault(
                key,
                room,
                "Máy lạnh rò nước",
                "Nước chảy xuống sàn",
                "URGENT",
                block,
                from,
                until,
                "Khóa để sửa máy lạnh");
    }

    @Test
    void receptionistCanBlockOnlyThroughLinkedMaintenanceAndReleaseRequiresCleaning() {
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT").toString())
                .doesNotContain("room.block.manage");
        var fault = fault(code(""), true, today, today.plusDays(3));
        long id = maintenance.create(fault);
        assertThat(maintenance.create(fault)).isEqualTo(id);
        assertThat(roomStatus()).isEqualTo("MAINTENANCE");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_id=? AND"
                                        + " room_block_id IS NOT NULL",
                                room))
                .isEqualTo(3);
        assertThat(
                        db.one(
                                        "SELECT blocked_rooms FROM room_type_inventory WHERE"
                                                + " room_type_id=? AND stay_date=?",
                                        type,
                                        today)
                                .n("blocked_rooms"))
                .isEqualTo(1);
        assertThatThrownBy(() -> create("PHONE", today, today.plusDays(1), 1))
                .isInstanceOf(AppException.class);
        maintenance.update(id, "start", "Kỹ thuật đã nhận việc");
        maintenance.update(id, "resolve", "Đã thay ống dẫn nước");
        assertThat(roomStatus()).isEqualTo("CLEANING");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_id=? AND"
                                        + " stay_date>=?",
                                room,
                                today))
                .isZero();
        var task = db.one("SELECT * FROM housekeeping_tasks WHERE room_id=?", room);
        assertThat(task.s("status")).isEqualTo("IN_PROGRESS");
        cleaning.clean(task.id("id"), "complete", "Đã vệ sinh sau sửa chữa");
        cleaning.clean(task.id("id"), "verify", "Đã nghiệm thu sau sửa chữa");
        assertThat(roomStatus()).isEqualTo("AVAILABLE");
        maintenance.update(id, "resolve", "Retry completion");
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks WHERE room_id=?", room))
                .isEqualTo(1);
    }

    @Test
    void occupiedRoomCanReportSmallFaultButOverlappingBlockRollsBackRequest() {
        long b = occupied(2);
        long count = db.count("SELECT COUNT(*) FROM maintenance_requests");
        assertThatThrownBy(
                        () -> maintenance.create(fault(code(""), true, today, today.plusDays(2))))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM maintenance_requests")).isEqualTo(count);
        long id = maintenance.create(fault(code(""), false, null, null));
        maintenance.update(id, "resolve", "Đã chỉnh vòi nước không cần khóa phòng");
        assertThat(roomStatus()).isEqualTo("OCCUPIED");
        assertThat(db.count("SELECT COUNT(*) FROM room_blocks")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks")).isZero();
    }

    @Test
    void futureMaintenanceDoesNotChangeTodaysOccupiedStateOrCreateCleaningWhenCancelled() {
        occupied(1);
        long id = maintenance.create(fault(code(""), true, today.plusDays(2), today.plusDays(3)));
        assertThat(roomStatus()).isEqualTo("OCCUPIED");
        maintenance.update(id, "cancel", "Đã đặt lịch kỹ thuật sang tuần sau");
        assertThat(roomStatus()).isEqualTo("OCCUPIED");
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks")).isZero();
    }

    @Test
    void customerHistoryCountsActualNightsWithoutDuplicatingMovesAndMasksDocuments()
            throws Exception {
        long b = occupied(2),
                customer =
                        db.one("SELECT customer_id FROM bookings WHERE id=?", b).id("customer_id");
        finish(b);
        db.update(
                "UPDATE booking_rooms SET actual_check_in_at=?,actual_check_out_at=? WHERE"
                        + " booking_id=?",
                ts(Instant.now().minus(Duration.ofDays(3))),
                ts(Instant.now().minus(Duration.ofDays(1))),
                b);
        room("102", type);
        var input = input("PHONE", today, today.plusDays(3), 1);
        var second =
                new Create(
                        input.requestKey(),
                        customer,
                        input.name(),
                        input.phone(),
                        input.email(),
                        input.source(),
                        null,
                        null,
                        input.rooms());
        long task = db.one("SELECT id FROM housekeeping_tasks WHERE room_id=?", room).id("id");
        cleaning.clean(task, "start", "Prepare next stay");
        cleaning.clean(task, "complete", "Finished room");
        cleaning.clean(task, "verify", "Ready for next stay");
        long b2 = bookings.create(second, bookings.preview(second).totalAmount());
        bookings.confirm(b2, "Second confirmed");
        stays.checkIn(
                b2,
                line(b2),
                List.of(new StayService.Guest("Nguyễn Minh", "ADULT", "ID", "012345678901")),
                "Arrived again");
        finish(b2);
        db.update(
                "UPDATE booking_rooms SET actual_check_in_at=?,actual_check_out_at=? WHERE"
                        + " booking_id=?",
                ts(Instant.now().minus(Duration.ofDays(4))),
                ts(Instant.now().minus(Duration.ofDays(1))),
                b2);
        db.update(
                "UPDATE customers SET"
                        + " document_type='CCCD',document_number_encrypted=?,document_number_hash=?"
                        + " WHERE id=?",
                crypto.encrypt("012345678901"),
                crypto.hash("012345678901"),
                customer);
        var h = history.get(customer);
        assertThat(h.visits()).isEqualTo(2);
        assertThat(h.nights()).isEqualTo(5);
        assertThat(h.spent())
                .isEqualByComparingTo(ledger.balance(b).net().add(ledger.balance(b2).net()));
        assertThat(h.tag()).isEqualTo("RETURNING");
        history.notes(customer, null, "Thích tầng cao, dị ứng hải sản", "Khách cung cấp sở thích");
        assertThatThrownBy(() -> history.notes(customer, null, "Old overwritten", "Stale screen"))
                .isInstanceOf(AppException.class);
        mvc.perform(get("/admin/customers/" + customer).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Thích tầng cao")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "012345678901"))));
        authenticate();
        assertThat(customers.search(0, 10, "090-123-4567", null).getItems()).hasSize(1);
    }

    void policy(String kind, String amount) {
        db.insert(
                "surcharge_policies",
                "code,name,kind,amount_type,amount_value,priority",
                code(""),
                "Phụ phí " + kind,
                kind,
                "FIXED",
                new BigDecimal(amount),
                50);
    }

    @Test
    void specialRequestsReviewChargesVatOnceAndBlockStaleOrIssuedInvoiceEdits() throws Exception {
        long b = occupied(2), line = line(b);
        policy("EXTRA_BED", "100000");
        policy("EXTRA_GUEST", "50000");
        var q = requests.preview(b, line, 1, 2);
        assertThat(q.total()).isEqualByComparingTo("220000");
        var f =
                new GuestRequestService.Form(
                        line,
                        q.revision(),
                        List.of("QUIET_ROOM", "LATE_ARRIVAL"),
                        1,
                        2,
                        "Giường gần cửa sổ",
                        "Khách muốn phòng yên tĩnh",
                        "Ghi chú riêng tuyệt đối không gửi khách",
                        "Khách yêu cầu thêm dịch vụ",
                        q.token());
        var before = ledger.balance(b).total();
        requests.save(b, f);
        assertThat(ledger.balance(b).total()).isEqualByComparingTo(before.add(q.total()));
        assertThatThrownBy(() -> requests.save(b, f)).isInstanceOf(AppException.class);
        assertThat(ledger.balance(b).total()).isEqualByComparingTo(before.add(q.total()));
        var q2 = requests.preview(b, line, 0, 0);
        requests.save(
                b,
                new GuestRequestService.Form(
                        line,
                        q2.revision(),
                        List.of("QUIET_ROOM"),
                        0,
                        0,
                        "Yên tĩnh",
                        "Yêu cầu phía khách",
                        "Ghi chú riêng",
                        "Bỏ giường phụ và phụ phí người",
                        q2.token()));
        assertThat(ledger.balance(b).total()).isEqualByComparingTo(before);
        assertThat(db.one("SELECT adults FROM booking_rooms WHERE id=?", line).n("adults"))
                .isEqualTo(1);
        mvc.perform(get("/admin/bookings/" + b).session(session))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "data-guest-preferences")));
    }

    @Test
    void missingOrChangedAddonPolicyCannotSilentlyChargeAndGuestNotesStayPrivate() {
        long b = occupied(1), line = line(b);
        assertThatThrownBy(() -> requests.preview(b, line, 1, 0)).isInstanceOf(AppException.class);
        policy("EXTRA_BED", "100000");
        var q = requests.preview(b, line, 1, 0);
        db.update("UPDATE surcharge_policies SET amount_value=150000 WHERE kind='EXTRA_BED'");
        assertThatThrownBy(
                        () ->
                                requests.save(
                                        b,
                                        new GuestRequestService.Form(
                                                line,
                                                q.revision(),
                                                List.of(),
                                                1,
                                                0,
                                                null,
                                                null,
                                                null,
                                                "Accept old quote",
                                                q.token())))
                .isInstanceOf(AppException.class);
        var q2 = requests.preview(b, line, 0, 0);
        requests.save(
                b,
                new GuestRequestService.Form(
                        line,
                        q2.revision(),
                        List.of(),
                        0,
                        0,
                        "Linen",
                        "Visible customer preference",
                        "PRIVATE ALLERGY NOTE",
                        "Save guest requests",
                        q2.token()));
        assertThat(
                        db.one(
                                        "SELECT body FROM guest_messages WHERE booking_id=? AND"
                                                + " message_type='CHANGE' ORDER BY id DESC",
                                        b)
                                .s("body"))
                .contains("Visible customer preference")
                .doesNotContain("PRIVATE ALLERGY NOTE");
    }

    @Test
    void confirmationQueuesEmailAndResendIsIdempotentWithRealDeliveryState() throws Exception {
        long b = create("PHONE", today, today.plusDays(2), 1);
        bookings.confirm(b, "Confirm for guest");
        assertThat(messages.history(b)).hasSize(1);
        assertThat(messages.history(b).get(0).s("status")).isEqualTo("PENDING");
        String key = code("");
        long message = messages.resend(b, key, "other@example.test");
        assertThat(messages.resend(b, key, "other@example.test")).isEqualTo(message);
        assertThat(messages.history(b)).hasSize(2);
        var body = db.one("SELECT body,qr_payload FROM guest_messages WHERE id=?", message);
        assertThat(body.s("body"))
                .contains("Nguyễn Minh", "Standard", today.toString(), "Chính sách hủy");
        assertThat(lookup.find(body.s("qr_payload"), "QR")).extracting(x -> x.id()).contains(b);
        worker.run();
        assertThat(messages.history(b)).allMatch(x -> x.s("status").equals("SENT"));
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(2))
                .send(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        worker.run();
        org.mockito.Mockito.verifyNoMoreInteractions(transport);
        dispatcher.dispatch();
        assertThat(db.count("SELECT COUNT(*) FROM notification_deliveries WHERE channel='IN_APP'"))
                .isPositive();
    }

    @Test
    void failedEmailRetriesWithoutClaimingSuccessAndBookingIsNotRolledBack() throws Exception {
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("SMTP credentials and private failure"))
                .when(transport)
                .send(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        long b = create("PHONE", today, today.plusDays(1), 1);
        bookings.confirm(b, "Confirmed even if mail offline");
        worker.run();
        var failed = messages.history(b).get(0);
        assertThat(failed.s("status")).isEqualTo("FAILED");
        assertThat(failed.n("attempts")).isEqualTo(1);
        assertThat(failed.s("last_error")).doesNotContain("credentials");
        assertThat(db.one("SELECT status FROM bookings WHERE id=?", b).s("status"))
                .isEqualTo("CONFIRMED");
        org.mockito.Mockito.doNothing()
                .when(transport)
                .send(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        db.update(
                "UPDATE outbox_events SET available_at=? WHERE aggregate_type='GUEST_MESSAGE'",
                ts(Instant.now().minusSeconds(1)));
        worker.run();
        assertThat(messages.history(b).get(0).s("status")).isEqualTo("SENT");
        assertThat(messages.history(b).get(0).n("attempts")).isEqualTo(2);
    }

    @Test
    void emailLeaseIsExclusiveAndExpiredFinalAttemptBecomesFailed() {
        long b = create("PHONE", today, today.plusDays(1), 1);
        bookings.confirm(b, "Confirmed for email");
        var claimed = mailQueue.claim();
        assertThat(claimed).isNotNull();
        assertThat(mailQueue.claim()).isNull();
        db.update(
                "UPDATE outbox_events SET attempts=5,locked_until=? WHERE id=?",
                ts(Instant.now().minusSeconds(1)),
                claimed.outbox());
        assertThat(mailQueue.claim()).isNull();
        assertThat(messages.history(b).get(0).s("status")).isEqualTo("FAILED");
    }

    @Test
    void maintenanceCleaningAndGridPagesRenderForReceptionistAndMutationsRequireCsrf()
            throws Exception {
        cleaning.request(room, "Prepare VIP room", true);
        long b = create("PHONE", today.plusDays(1), today.plusDays(2), 1);
        bookings.confirm(b, "Tomorrow arrival");
        for (String url :
                List.of(
                        "/admin/receptionist/housekeeping",
                        "/admin/receptionist/housekeeping?status=COMPLETED",
                        "/admin/maintenance",
                        "/admin/maintenance?room=" + room,
                        "/admin/rooms/grid",
                        "/admin/bookings/" + b)) {
            mvc.perform(get(url).session(session)).andExpect(status().isOk());
        }
        mvc.perform(
                        post("/admin/rooms/" + room + "/dirty")
                                .session(session)
                                .param("notes", "Needs cleaning"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/bookings/" + b + "/resend-email")
                                .session(session)
                                .with(csrf())
                                .param("key", code(""))
                                .param("recipient", "bad\r\nrecipient@example.test"))
                .andExpect(status().is3xxRedirection());
        authenticate();
        assertThat(messages.history(b)).hasSize(1);
    }

    @Autowired com.example.hotelmanagement.modules.reception.QrCodeSupport qr;
    @Autowired com.example.hotelmanagement.modules.invoice.InvoiceService invoices;

    @Test
    void mimeTransportKeepsVietnameseAndQrAttachmentWithoutContactingSmtp() throws Exception {
        class Capture extends org.springframework.mail.javamail.JavaMailSenderImpl {
            jakarta.mail.internet.MimeMessage captured;

            @Override
            public void send(jakarta.mail.internet.MimeMessage message) {
                captured = message;
            }
        }
        Capture sender = new Capture();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<
                        org.springframework.mail.javamail.JavaMailSender>
                provider =
                        org.mockito.Mockito.mock(
                                org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(sender);
        var transport =
                new com.example.hotelmanagement.modules.reception.GuestEmailTransport(provider, qr);
        org.springframework.test.util.ReflectionTestUtils.setField(transport, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
                transport, "from", "hotel@example.test");
        transport.send(
                "guest@example.test",
                "Xác nhận đặt phòng",
                "Xin chào Nguyễn Minh",
                "HOTEL-BOOKING:test-payload");
        sender.captured.saveChanges();
        assertThat(sender.captured.getSubject()).isEqualTo("Xác nhận đặt phòng");
        var multipart = (jakarta.mail.Multipart) sender.captured.getContent();
        assertThat(multipart.getCount()).isEqualTo(2);
        Object body = multipart.getBodyPart(0).getContent();
        while (body instanceof jakarta.mail.Multipart nested)
            body = nested.getBodyPart(0).getContent();
        assertThat(body.toString()).contains("Nguyễn Minh");
        assertThat(qr.read(multipart.getBodyPart(1).getInputStream().readAllBytes()))
                .isEqualTo("HOTEL-BOOKING:test-payload");
    }

    @Test
    void permissionsAndIssuedInvoicesPreventGuestCareMutations() throws Exception {
        long b = occupied(1), line = line(b);
        var q = requests.preview(b, line, 0, 0);
        finish(b);
        invoices.issue(b, null, null);
        assertThatThrownBy(
                        () ->
                                requests.save(
                                        b,
                                        new GuestRequestService.Form(
                                                line,
                                                q.revision(),
                                                List.of(),
                                                0,
                                                0,
                                                null,
                                                null,
                                                null,
                                                "Cannot modify issued invoice",
                                                q.token())))
                .isInstanceOf(AppException.class);
        var context = SecurityContextHolder.getContext();
        var auth = context.getAuthentication();
        var limited =
                new SecurityContextImpl(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                auth.getPrincipal(),
                                null,
                                List.of(
                                        new org.springframework.security.core.authority
                                                .SimpleGrantedAuthority("booking.read.any"))));
        SecurityContextHolder.setContext(limited);
        try {
            assertThatThrownBy(() -> cleaning.request(room, "No cleaning permission", false))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> maintenance.create(fault(code(""), false, null, null)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> messages.resend(b, code(""), "guest@example.test"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(
                            () ->
                                    history.get(
                                            db.one("SELECT customer_id FROM bookings WHERE id=?", b)
                                                    .id("customer_id")))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            SecurityContextHolder.setContext(context);
        }
    }

    @Test
    void expiredMaintenanceStillRequiresResolutionBeforeInspection() {
        long id = maintenance.create(fault(code(""), true, today, today.plusDays(1)));
        db.update("UPDATE room_blocks SET status='RELEASED' WHERE maintenance_request_id=?", id);
        db.update("DELETE FROM room_night_claims WHERE room_id=? AND stay_date>=?", room, today);
        db.update("UPDATE rooms SET status='CLEANING' WHERE id=?", room);
        assertThat(inventory.unresolvedMaintenance(room)).isTrue();
        assertThatThrownBy(() -> cleaning.request(room, "Cannot clean before repair", false))
                .isInstanceOf(AppException.class);
        maintenance.update(id, "resolve", "Repair finished after scheduled end");
        assertThat(inventory.unresolvedMaintenance(room)).isFalse();
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM housekeeping_tasks WHERE room_id=? AND"
                                        + " status='IN_PROGRESS'",
                                room))
                .isEqualTo(1);
    }

    @Test
    void cancellationVoidsUnconsumedAddonsAndQueuesGuestCancellation() {
        long b = create("PHONE", today.plusDays(1), today.plusDays(2), 1), line = line(b);
        bookings.confirm(b, "Confirmed for tomorrow");
        policy("EXTRA_BED", "100000");
        var q = requests.preview(b, line, 1, 0);
        requests.save(
                b,
                new GuestRequestService.Form(
                        line,
                        q.revision(),
                        List.of("QUIET_ROOM"),
                        1,
                        0,
                        "Cửa sổ",
                        "Phòng yên tĩnh",
                        "INTERNAL PRIVATE",
                        "Guest requested bed",
                        q.token()));
        assertThat(ledger.balance(b).total()).isEqualByComparingTo("990000");
        bookings.cancel(b, null, "Guest cancelled before arrival");
        assertThat(ledger.balance(b).total()).isEqualByComparingTo("0");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_charges WHERE booking_id=? AND"
                                    + " source_key LIKE 'GUEST-ADDON:%' AND status='VOIDED'",
                                b))
                .isEqualTo(2);
        assertThat(messages.history(b).get(0).s("message_type")).isEqualTo("CANCELLATION");
        assertThat(
                        db.one(
                                        "SELECT body FROM guest_messages WHERE booking_id=? AND"
                                            + " message_type='CANCELLATION'",
                                        b)
                                .s("body"))
                .doesNotContain("INTERNAL PRIVATE");
    }
}
