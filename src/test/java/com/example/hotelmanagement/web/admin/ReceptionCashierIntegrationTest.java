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
class ReceptionCashierIntegrationTest {
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

    @Autowired com.example.hotelmanagement.modules.hotelservice.HotelServiceCatalogue catalogue;
    @Autowired FrontDeskCashierService cashier;
    @Autowired RefundRequestService refunds;
    @Autowired BankTransferQr bank;
    @Autowired PaymentProofStorage proofs;
    @Autowired com.example.hotelmanagement.modules.reception.QrCodeSupport qr;
    @Autowired com.example.hotelmanagement.modules.reception.ReceptionOutboxDispatcher dispatcher;
    @Autowired com.example.hotelmanagement.modules.invoice.InvoiceService invoices;
    @Autowired com.example.hotelmanagement.modules.hotel.service.HotelMediaStorage media;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path proofDir;
    MockHttpSession adminSession;

    @BeforeEach
    void configureProof() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                proofs, "directory", proofDir.toString());
    }

    @AfterEach
    void restoreProof() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                proofs, "directory", "uploads/payments-private");
    }

    long occupied(int nights) {
        long b = create("PHONE", today, today.plusDays(nights), 1);
        bookings.confirm(b, "Confirmed");
        stays.checkIn(
                b,
                line(b),
                List.of(new StayService.Guest("Nguyễn Minh", "ADULT", "ID", "000000000123")),
                "Arrived");
        return b;
    }

    long admin() throws Exception {
        if (adminSession == null) {
            users.createUser(
                    CreateUserRequest.builder()
                            .fullName("Thu ngân quản lý")
                            .email("cashier.admin@example.test")
                            .password("InitialPass123")
                            .roles(Set.of("ADMIN"))
                            .build());
            adminSession =
                    (MockHttpSession)
                            mvc.perform(
                                            post("/admin/login")
                                                    .with(csrf())
                                                    .param("email", "cashier.admin@example.test")
                                                    .param("password", "InitialPass123"))
                                    .andExpect(status().is3xxRedirection())
                                    .andReturn()
                                    .getRequest()
                                    .getSession(false);
        }
        SecurityContextHolder.setContext(
                (SecurityContext) adminSession.getAttribute("SPRING_SECURITY_CONTEXT"));
        return ((com.example.hotelmanagement.security.authentication.CustomUserDetails)
                        SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .getId();
    }

    long service(String code, String name, String unit, String price) {
        var rows = db.rows("SELECT id FROM service_categories WHERE code=?", code);
        long cat =
                rows.isEmpty()
                        ? db.insert("service_categories", "code,name", code, name)
                        : rows.get(0).id("id");
        return db.insert(
                "services",
                "category_id,code,name,unit,unit_price,tax_percent",
                cat,
                code,
                name,
                unit,
                new BigDecimal(price),
                new BigDecimal("10"));
    }

    byte[] png() throws Exception {
        var image =
                new java.awt.image.BufferedImage(
                        120, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(20, 110, 140));
        graphics.fillRect(0, 0, 120, 40);
        graphics.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    org.springframework.mock.web.MockMultipartFile proof() throws Exception {
        return new org.springframework.mock.web.MockMultipartFile(
                "proof", "transfer.png", "image/png", png());
    }

    @Test
    void serviceSnapshotsTaxAndVoidMatchThePlanAndRejectAnIssuedInvoice() {
        long b = occupied(1),
                cola = service("MINIBAR", "Nước ngọt Coca-Cola", "Lon", "20000"),
                laundry = service("LAUNDRY", "Giặt là", "kg", "40000");
        long first =
                cashier.service(
                        b,
                        line(b),
                        cola,
                        new BigDecimal("2"),
                        code(""),
                        "Minibar",
                        new BigDecimal("20000"),
                        new BigDecimal("10"));
        long second =
                cashier.service(
                        b,
                        line(b),
                        laundry,
                        new BigDecimal("1.500"),
                        code(""),
                        "Giặt là",
                        new BigDecimal("40000"),
                        new BigDecimal("10"));
        assertThat(ledger.balance(b).debt()).isEqualByComparingTo("990000");
        assertThat(cashier.snapshot(b).categories()).hasSize(2);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_charges WHERE service_usage_id IS NOT"
                                    + " NULL AND kind IN ('SERVICE','TAX')"))
                .isEqualTo(4);
        catalogue.voidUsage(b, second, "Nhầm phòng");
        catalogue.voidUsage(b, second, "Retry");
        assertThat(ledger.balance(b).debt()).isEqualByComparingTo("924000");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_charges WHERE service_usage_id=? AND"
                                    + " status='VOIDED'",
                                second))
                .isEqualTo(2);
        db.update("UPDATE services SET unit_price=99999,tax_percent=20 WHERE id=?", cola);
        assertThat(
                        db.one("SELECT unit_price_snapshot FROM service_usages WHERE id=?", first)
                                .money("unit_price_snapshot"))
                .isEqualByComparingTo("20000");
        payments.collect(b, code(""), "PAYMENT", "BANK_TRANSFER", ledger.balance(b).debt(), null);
        stays.checkOut(b, line(b), "Departed");
        invoices.issue(b, null, null);
        assertThatThrownBy(() -> catalogue.voidUsage(b, first, "Undo after invoice"))
                .isInstanceOf(AppException.class);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM outbox_events WHERE"
                                    + " event_type='BOOKING_SERVICE_ADDED'"))
                .isEqualTo(2);
    }

    @Test
    void fractionalQuantitiesRoundToWholeVndAndReviewedPricesAndKeysAreEnforced() {
        long b = occupied(1), s = service("LAUNDRY", "Giặt là", "kg", "1001");
        String key = code("");
        long id =
                cashier.service(
                        b,
                        line(b),
                        s,
                        new BigDecimal("0.333"),
                        key,
                        null,
                        new BigDecimal("1001"),
                        new BigDecimal("10"));
        assertThat(ledger.balance(b).debt()).isEqualByComparingTo("880366");
        assertThat(
                        db.one(
                                        "SELECT amount FROM booking_charges WHERE"
                                            + " service_usage_id=? AND kind='ADJUSTMENT'",
                                        id)
                                .money("amount"))
                .isEqualByComparingTo("-0.33");
        db.update("UPDATE services SET unit_price=2000 WHERE id=?", s);
        assertThat(
                        cashier.service(
                                b,
                                line(b),
                                s,
                                new BigDecimal("0.333"),
                                key,
                                null,
                                new BigDecimal("1001"),
                                new BigDecimal("10")))
                .isEqualTo(id);
        assertThatThrownBy(
                        () ->
                                cashier.service(
                                        b,
                                        line(b),
                                        s,
                                        BigDecimal.ONE,
                                        code(""),
                                        null,
                                        new BigDecimal("1001"),
                                        new BigDecimal("10")))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM service_usages")).isEqualTo(1);
        catalogue.voidUsage(b, id, "Nhập nhầm số lượng");
        assertThat(ledger.balance(b).debt()).isEqualByComparingTo("880000");
    }

    @Test
    void depositAndInstallmentsTrackBankReferencesAndTheCurrentCashShift() throws Exception {
        long policy =
                db.insert(
                        "deposit_policies",
                        "code,name,amount_type,amount_value,payment_due_minutes",
                        "FIXED_TEST",
                        "Cọc thử",
                        "FIXED",
                        new BigDecimal("500000"),
                        15);
        db.update("UPDATE hotel_settings SET default_deposit_policy_id=? WHERE id=1", policy);
        long b = create("PHONE", today, today.plusDays(2), 1);
        var initial = cashier.snapshot(b);
        assertThat(initial.balance().depositShortfall()).isEqualByComparingTo("500000");
        String key = code("");
        long paid =
                cashier.collect(
                        b,
                        key,
                        "DEPOSIT",
                        "BANK_TRANSFER",
                        new BigDecimal("500000"),
                        "Cọc phòng",
                        "BANK-001",
                        initial.token(),
                        proof());
        assertThat(
                        cashier.collect(
                                b,
                                key,
                                "DEPOSIT",
                                "BANK_TRANSFER",
                                new BigDecimal("500000"),
                                "Cọc phòng",
                                "BANK-001",
                                initial.token(),
                                proof()))
                .isEqualTo(paid);
        assertThat(cashier.snapshot(b).balance().depositShortfall()).isEqualByComparingTo("0");
        bookings.confirm(b, "Cọc đủ");
        stays.checkIn(
                b,
                line(b),
                List.of(new StayService.Guest("Guest", "ADULT", "ID", "000000000123")),
                "Arrived");
        var mid = cashier.snapshot(b);
        assertThatThrownBy(
                        () ->
                                cashier.collect(
                                        b,
                                        code(""),
                                        "PAYMENT",
                                        "CASH",
                                        new BigDecimal("1000000"),
                                        null,
                                        null,
                                        mid.token(),
                                        null))
                .isInstanceOf(AppException.class);
        payments.open(new BigDecimal("100000"));
        cashier.collect(
                b,
                code(""),
                "PAYMENT",
                "CASH",
                new BigDecimal("1000000"),
                null,
                null,
                mid.token(),
                null);
        var end = cashier.snapshot(b);
        assertThat(end.balance().net()).isEqualByComparingTo("1500000");
        assertThat(end.balance().debt()).isEqualByComparingTo("260000");
        assertThat(end.shift().expected()).isEqualByComparingTo("1100000");
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(2);
        assertThat(
                        db.one(
                                        "SELECT work_session_id FROM payment_transactions WHERE"
                                            + " method='CASH'")
                                .id("work_session_id"))
                .isEqualTo(end.shift().id());
    }

    @Test
    void expiredHoldsAndStaleLedgerAreRejectedAndInvalidProofRollsBackMoney() {
        long b = create("PHONE", today, today.plusDays(1), 1);
        db.update(
                "UPDATE bookings SET hold_expires_at=?,deposit_required_amount=500000 WHERE id=?",
                ts(Instant.now().minusSeconds(1)),
                b);
        var p = cashier.snapshot(b);
        assertThat(p.balance().canDeposit()).isFalse();
        assertThatThrownBy(
                        () ->
                                cashier.collect(
                                        b,
                                        code(""),
                                        "DEPOSIT",
                                        "BANK_TRANSFER",
                                        new BigDecimal("1"),
                                        null,
                                        null,
                                        p.token(),
                                        null))
                .isInstanceOf(AppException.class);
        db.update(
                "UPDATE bookings SET hold_expires_at=?,deposit_required_amount=0 WHERE id=?",
                ts(Instant.now().plusSeconds(900)),
                b);
        var refreshed = cashier.snapshot(b);
        String token = refreshed.token();
        payments.collect(b, code(""), "PAYMENT", "BANK_TRANSFER", new BigDecimal("100000"), null);
        assertThatThrownBy(
                        () ->
                                cashier.collect(
                                        b,
                                        code(""),
                                        "PAYMENT",
                                        "BANK_TRANSFER",
                                        new BigDecimal("100000"),
                                        null,
                                        null,
                                        token,
                                        null))
                .isInstanceOf(AppException.class);
        var now = cashier.snapshot(b);
        long events = db.count("SELECT COUNT(*) FROM outbox_events");
        var invalid =
                new org.springframework.mock.web.MockMultipartFile(
                        "proof", "fake.png", "image/png", new byte[] {1, 2, 3});
        assertThatThrownBy(
                        () ->
                                cashier.collect(
                                        b,
                                        code(""),
                                        "PAYMENT",
                                        "BANK_TRANSFER",
                                        new BigDecimal("100000"),
                                        null,
                                        "BAD-PROOF",
                                        now.token(),
                                        invalid))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM outbox_events")).isEqualTo(events);
    }

    @Test
    void bankReferenceCannotBeReusedAndProofIsOnlyAvailableToAuthorizedStaff() throws Exception {
        long a = create("PHONE", today, today.plusDays(1), 1),
                b = create("PHONE", today.plusDays(1), today.plusDays(2), 1);
        long p =
                cashier.collect(
                        a,
                        code(""),
                        "PAYMENT",
                        "BANK_TRANSFER",
                        new BigDecimal("100000"),
                        null,
                        "BANK-UNIQUE",
                        cashier.snapshot(a).token(),
                        proof());
        assertThatThrownBy(
                        () ->
                                cashier.collect(
                                        b,
                                        code(""),
                                        "PAYMENT",
                                        "BANK_TRANSFER",
                                        new BigDecimal("100000"),
                                        null,
                                        "BANK-UNIQUE",
                                        cashier.snapshot(b).token(),
                                        proof()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(1);
        mvc.perform(get("/admin/receptionist/cashier/proofs/{id}", p).session(session))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(get("/admin/receptionist/cashier/proofs/{id}", p))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void refundRequestsNotifyApproversAndApprovalRecordsOneLinkedRefund() throws Exception {
        long b = occupied(1),
                parent =
                        payments.collect(
                                b,
                                code(""),
                                "PAYMENT",
                                "BANK_TRANSFER",
                                new BigDecimal("1000000"),
                                "Khách tạm ứng thêm");
        String key = code("");
        long r =
                refunds.request(
                        b,
                        key,
                        parent,
                        new BigDecimal("120000"),
                        "BANK_TRANSFER",
                        "Khách yêu cầu hoàn tiền thừa");
        assertThat(
                        refunds.request(
                                b,
                                key,
                                parent,
                                new BigDecimal("120000"),
                                "BANK_TRANSFER",
                                "Khách yêu cầu hoàn tiền thừa"))
                .isEqualTo(r);
        assertThatThrownBy(() -> refunds.decide(b, r, true, "Đã hoàn tiền cho khách", null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions WHERE kind='REFUND'"))
                .isZero();
        long approver = admin();
        dispatcher.dispatch();
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM notifications WHERE"
                                    + " type='RECEPTION_BOOKING_REFUND_REQUESTED' AND user_id=?",
                                approver))
                .isEqualTo(1);
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM notifications WHERE"
                                    + " type='RECEPTION_BOOKING_REFUND_REQUESTED' AND user_id=?",
                                user))
                .isZero();
        long paid = refunds.decide(b, r, true, "Đã kiểm tra và hoàn tiền cho khách", "RETURN-001");
        assertThat(refunds.decide(b, r, true, "Retry completed decision", "RETURN-001"))
                .isEqualTo(paid);
        assertThat(
                        db.one(
                                        "SELECT parent_payment_id FROM payment_transactions WHERE"
                                            + " id=?",
                                        paid)
                                .id("parent_payment_id"))
                .isEqualTo(parent);
        assertThat(
                        db.one("SELECT approved_by FROM payment_transactions WHERE id=?", paid)
                                .id("approved_by"))
                .isEqualTo(approver);
        assertThat(ledger.balance(b).debt()).isEqualByComparingTo("0");
        dispatcher.dispatch();
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM notifications WHERE"
                                    + " type='RECEPTION_BOOKING_REFUND_RESOLVED' AND user_id=?",
                                user))
                .isEqualTo(1);
    }

    @Test
    void refundsEnforceBothLimitsAndRejectAStaleApproval() throws Exception {
        long b = occupied(1),
                parent =
                        payments.collect(
                                b,
                                code(""),
                                "PAYMENT",
                                "BANK_TRANSFER",
                                new BigDecimal("1000000"),
                                "Khách tạm ứng thêm");
        assertThatThrownBy(
                        () ->
                                refunds.request(
                                        b,
                                        code(""),
                                        parent,
                                        new BigDecimal("120001"),
                                        "BANK_TRANSFER",
                                        "Hoàn tiền thừa cho khách"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () ->
                                refunds.request(
                                        b,
                                        code(""),
                                        parent,
                                        new BigDecimal("120000"),
                                        "BANK_TRANSFER",
                                        "Short"))
                .isInstanceOf(AppException.class);
        long r =
                refunds.request(
                        b,
                        code(""),
                        parent,
                        new BigDecimal("120000"),
                        "BANK_TRANSFER",
                        "Hoàn tiền thừa cho khách");
        admin();
        payments.refund(
                b,
                code(""),
                parent,
                "BANK_TRANSFER",
                new BigDecimal("30000"),
                "Đã hoàn trước một phần");
        assertThatThrownBy(() -> refunds.decide(b, r, true, "Đã hoàn tiền cho khách", null))
                .isInstanceOf(AppException.class);
        assertThat(db.one("SELECT status FROM refund_requests WHERE id=?", r).s("status"))
                .isEqualTo("PENDING");
        refunds.decide(b, r, false, "Số dư cần hoàn đã thay đổi", null);
        assertThat(db.one("SELECT status FROM refund_requests WHERE id=?", r).s("status"))
                .isEqualTo("REJECTED");
        assertThat(ledger.balance(b).refundDue()).isEqualByComparingTo("90000");
    }

    @Test
    void onlyTheRequesterMayWithdrawAndRequestsAreNotFundReservations() throws Exception {
        long b = occupied(1),
                parent =
                        payments.collect(
                                b,
                                code(""),
                                "PAYMENT",
                                "BANK_TRANSFER",
                                new BigDecimal("1000000"),
                                "Khách tạm ứng thêm");
        long r =
                refunds.request(
                        b,
                        code(""),
                        parent,
                        new BigDecimal("120000"),
                        "BANK_TRANSFER",
                        "Hoàn tiền thừa cho khách");
        assertThat(ledger.balance(b).refundDue()).isEqualByComparingTo("120000");
        admin();
        assertThatThrownBy(() -> refunds.withdraw(b, r))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        authenticate();
        refunds.withdraw(b, r);
        refunds.withdraw(b, r);
        assertThat(db.one("SELECT status FROM refund_requests WHERE id=?", r).s("status"))
                .isEqualTo("WITHDRAWN");
        assertThat(refunds.queue()).isEmpty();
    }

    @Test
    void qrMatchesThePublishedReferenceAndBankSettingsUsePermissionAndRevision() throws Exception {
        String expected =
                "00020101021238560010A0000007270126000697041501121133666688880208QRIBFTTA53037045405790005802VN62220818Ung"
                    + " Ho Quy Vac Xin63043ACF";
        assertThat(BankTransferQr.payload("970415", "113366668888", "79000", "Ung Ho Quy Vac Xin"))
                .isEqualTo(expected);
        long b = create("PHONE", today, today.plusDays(1), 1);
        assertThatThrownBy(
                        () ->
                                bank.save(
                                        new BankTransferQr.Settings(
                                                "970415", "113366668888", "NGUYEN VAN A", 0)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        admin();
        bank.save(new BankTransferQr.Settings("970415", "113366668888", "NGUYEN VAN A", 0));
        assertThatThrownBy(
                        () ->
                                bank.save(
                                        new BankTransferQr.Settings(
                                                "970415", "113366668888", "NGUYEN VAN A", 0)))
                .isInstanceOf(AppException.class);
        var c = bank.code(b, new BigDecimal("79000"));
        assertThat(c.memo()).hasSize(25);
        assertThat(c.memo())
                .isEqualTo(
                        db.one("SELECT booking_code FROM bookings WHERE id=?", b)
                                .s("booking_code")
                                .substring(0, 25));
        byte[] image = Base64.getDecoder().decode(c.image().substring(c.image().indexOf(',') + 1));
        assertThat(qr.read(image)).isEqualTo(c.payload());
    }

    @Test
    void companyInvoicePreservesVietnameseLogoAndFreezesCharges() throws Exception {
        long b = occupied(1), s = service("MINIBAR", "Nước ngọt có ga", "Lon", "20000");
        long usage =
                cashier.service(
                        b,
                        line(b),
                        s,
                        new BigDecimal("2"),
                        code(""),
                        "Minibar",
                        new BigDecimal("20000"),
                        new BigDecimal("10"));
        payments.collect(b, code(""), "PAYMENT", "BANK_TRANSFER", ledger.balance(b).debt(), null);
        stays.checkOut(b, line(b), "Khách trả phòng");
        byte[] logoBytes = png();
        String logo =
                new TransactionTemplate(tx)
                        .execute(
                                t ->
                                        media.store(
                                                new org.springframework.mock.web.MockMultipartFile(
                                                        "logo",
                                                        "hotel.png",
                                                        "image/png",
                                                        logoBytes)));
        db.update("UPDATE hotel_settings SET logo_url=? WHERE id=1", logo);
        try {
            long id =
                    cashier.invoice(
                            b,
                            "Công ty Dịch vụ Hà Nội",
                            "Đống Đa, Hà Nội",
                            "0101234567",
                            cashier.snapshot(b).token());
            byte[] pdf = invoices.download(id).data();
            try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertThat(text)
                        .contains(
                                "Công ty Dịch vụ Hà Nội",
                                "0101234567",
                                "Đống Đa, Hà Nội",
                                "Nước ngọt có ga",
                                "Đại diện khách sạn");
                assertThat(doc.getPage(0).getResources().getXObjectNames()).hasSize(2);
                javax.imageio.ImageIO.write(
                        new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 110),
                        "png",
                        java.nio.file.Path.of("target/cashier-invoice-preview.png").toFile());
            }
            assertThat(invoices.issue(b, "New address", "New tax", "Other company")).isEqualTo(id);
            assertThat(
                            db.one("SELECT buyer_company_name FROM invoices WHERE id=?", id)
                                    .s("buyer_company_name"))
                    .isEqualTo("Công ty Dịch vụ Hà Nội");
            assertThatThrownBy(() -> catalogue.voidUsage(b, usage, "Hủy sau hóa đơn"))
                    .isInstanceOf(AppException.class);
        } finally {
            db.update("UPDATE hotel_settings SET logo_url=NULL WHERE id=1");
            java.nio.file.Files.deleteIfExists(
                    media.root().resolve(logo.substring("/media/hotel/".length())));
        }
    }

    @Test
    void httpFormsJsonAndCsrfEnforceTheNewCashierContract() throws Exception {
        long b = occupied(1);
        mvc.perform(get("/admin/receptionist/cashier").session(session))
                .andExpect(status().isOk())
                .andExpect(
                        content().string(org.hamcrest.Matchers.containsString("cashier-dialog")));
        String response =
                mvc.perform(get("/admin/receptionist/cashier/{id}", b).session(session))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.balance.debt").value(880000))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = mapper.readValue(response, Map.class).get("token").toString();
        mvc.perform(
                        post("/admin/receptionist/cashier/{id}/collect", b)
                                .session(session)
                                .param("key", code(""))
                                .param("kind", "PAYMENT")
                                .param("method", "BANK_TRANSFER")
                                .param("amount", "100000")
                                .param("token", token))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/admin/receptionist/cashier/{id}/collect", b)
                                .file(proof())
                                .session(session)
                                .with(csrf())
                                .param("key", code(""))
                                .param("kind", "PAYMENT")
                                .param("method", "BANK_TRANSFER")
                                .param("amount", "100000")
                                .param("reference", "HTTP-BANK-001")
                                .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber());
        mvc.perform(
                        post("/admin/receptionist/cashier/{id}/refund", b)
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"parent\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/receptionist/cashier/{id}/refund", b)
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .accept("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/admin/bookings/{id}", b).session(session))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(org.hamcrest.Matchers.containsString("data-open-cashier")));
    }

    @Test
    void concurrentApprovalsCannotRefundMoreThanTheCurrentSurplus() throws Exception {
        long b = occupied(1),
                parent =
                        payments.collect(
                                b,
                                code(""),
                                "PAYMENT",
                                "BANK_TRANSFER",
                                new BigDecimal("1000000"),
                                "Khách tạm ứng thêm");
        long first =
                refunds.request(
                        b,
                        code(""),
                        parent,
                        new BigDecimal("100000"),
                        "BANK_TRANSFER",
                        "Hoàn tiền dư lần thứ nhất");
        long second =
                refunds.request(
                        b,
                        code(""),
                        parent,
                        new BigDecimal("100000"),
                        "BANK_TRANSFER",
                        "Hoàn tiền dư lần thứ hai");
        admin();
        var principal = SecurityContextHolder.getContext().getAuthentication();
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (long request : List.of(first, second))
                results.add(
                        pool.submit(
                                () -> {
                                    var context = SecurityContextHolder.createEmptyContext();
                                    context.setAuthentication(principal);
                                    SecurityContextHolder.setContext(context);
                                    ready.countDown();
                                    start.await();
                                    try {
                                        refunds.decide(
                                                b,
                                                request,
                                                true,
                                                "Đã đối chiếu và hoàn tiền",
                                                null);
                                        return true;
                                    } catch (AppException ex) {
                                        return false;
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int completed = 0;
            for (var result : results)
                if (result.get(20, java.util.concurrent.TimeUnit.SECONDS)) completed++;
            assertThat(completed).isEqualTo(1);
            assertThat(ledger.balance(b).refundDue()).isEqualByComparingTo("20000");
            assertThat(db.count("SELECT COUNT(*) FROM payment_transactions WHERE kind='REFUND'"))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
