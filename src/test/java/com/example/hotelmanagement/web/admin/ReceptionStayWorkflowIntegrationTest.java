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
class ReceptionStayWorkflowIntegrationTest {
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
        db.update(
                "UPDATE room_types SET"
                    + " base_price=800000,is_active=TRUE,max_adults=2,max_children=1,max_occupancy=3"
                    + " WHERE id=?",
                type);
        db.update("UPDATE room_types SET base_price=1200000,is_active=TRUE WHERE id=?", deluxe);
        db.update("UPDATE hotel_settings SET default_tax_percent=10 WHERE id=1");
        floor =
                db.insert(
                        "floors", "code,name,area_name,floor_number", "DESK_F1", "Tầng 1", "A", 1);
        db.update(
                "UPDATE room_types SET max_adults=2,max_children=1,max_occupancy=3 WHERE id=?",
                type);
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

    @Autowired StayWorkflowService workflow;
    @Autowired com.example.hotelmanagement.modules.reception.ReceptionBookingLookup lookup;
    @Autowired com.example.hotelmanagement.modules.reception.QrCodeSupport qr;
    @Autowired com.example.hotelmanagement.modules.invoice.InvoiceService invoices;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    List<StayService.Guest> guests() {
        return List.of(
                new StayService.Guest(
                        "Nguyễn Minh",
                        "ADULT",
                        "ID",
                        "000000000123",
                        LocalDate.of(1990, 1, 2),
                        "VN"));
    }

    long confirmed(int nights) {
        long id = create("PHONE", today, today.plusDays(nights), 1);
        bookings.confirm(id, "Confirmed");
        return id;
    }

    void arrival(long id) {
        var p = workflow.checkinPreview(id, line(id));
        workflow.checkin(
                id,
                line(id),
                guests(),
                "Arrived",
                code(""),
                "BANK_TRANSFER",
                BigDecimal.ZERO,
                null,
                p.projectedDebt(),
                p.version());
    }

    void admin() throws Exception {
        users.createUser(
                CreateUserRequest.builder()
                        .fullName("Stay admin")
                        .email("stay.admin@example.test")
                        .password("InitialPass123")
                        .roles(Set.of("ADMIN"))
                        .build());
        var authSession =
                (MockHttpSession)
                        mvc.perform(
                                        post("/admin/login")
                                                .with(csrf())
                                                .param("email", "stay.admin@example.test")
                                                .param("password", "InitialPass123"))
                                .andExpect(status().is3xxRedirection())
                                .andReturn()
                                .getRequest()
                                .getSession(false);
        SecurityContextHolder.setContext(
                (SecurityContext) authSession.getAttribute("SPRING_SECURITY_CONTEXT"));
    }

    void surcharge(String kind, String amountType, String amount) {
        db.insert(
                "surcharge_policies",
                "code,name,kind,amount_type,amount_value,from_minutes,priority",
                "TEST_" + kind,
                "Phụ phí thử nghiệm",
                kind,
                amountType,
                new BigDecimal(amount),
                0,
                100);
    }

    @Test
    void earlyHourlyFeeRoundsUpAndRegistersEncryptedGuestsWithBirthDates() throws Exception {
        var f =
                new Create(
                        code(""),
                        null,
                        "Đại diện",
                        "0901234567",
                        null,
                        "PHONE",
                        null,
                        null,
                        List.of(new RoomInput(type, today, today.plusDays(1), 1, 1)));
        long id = bookings.create(f, bookings.preview(f).totalAmount()), line = line(id);
        bookings.confirm(id, "Confirmed");
        surcharge("EARLY_CHECKIN", "PER_HOUR", "100000");
        db.update(
                "UPDATE booking_rooms SET expected_check_in_at=? WHERE id=?",
                ts(Instant.now().plusSeconds(125 * 60)),
                line);
        var p = workflow.checkinPreview(id, line);
        assertThat(p.lines().get(0).preview().surcharge().billedHours()).isEqualTo(3);
        assertThat(p.projectedDebt()).isEqualByComparingTo("1210000");
        mvc.perform(
                        get("/admin/bookings/{id}/checkin-preview", id)
                                .param("line", Long.toString(line))
                                .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].preview.surcharge.billedHours").value(3))
                .andExpect(jsonPath("$.lines[0].preview.surcharge.tax").value(30000));
        authenticate();

        var people = new ArrayList<>(guests());
        people.add(
                new StayService.Guest(
                        "Trẻ em", "CHILD", null, null, LocalDate.of(2018, 8, 15), "VN"));
        workflow.checkin(
                id,
                line,
                people,
                "Arrived early",
                code(""),
                "BANK_TRANSFER",
                new BigDecimal("1210000"),
                null,
                p.projectedDebt(),
                p.version());
        assertThat(db.count("SELECT COUNT(*) FROM booking_guests")).isEqualTo(2);
        var g = db.one("SELECT * FROM booking_guests WHERE guest_type='ADULT'");
        assertThat(g.s("document_number_encrypted")).doesNotContain("000000000123");
        assertThat(g.s("document_number_hash")).isEqualTo(crypto.hash("000000000123"));
        assertThat(g.date("date_of_birth")).isEqualTo(LocalDate.of(1990, 1, 2));
        assertThat(ledger.balance(id).debt()).isEqualByComparingTo("0");
        workflow.checkin(
                id,
                line,
                people,
                "Retry",
                code(""),
                "BANK_TRANSFER",
                new BigDecimal("1210000"),
                null,
                p.projectedDebt(),
                p.version());
        assertThat(db.count("SELECT COUNT(*) FROM booking_charges WHERE kind='SURCHARGE'"))
                .isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM stay_guests")).isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(1);
        var out = workflow.checkoutPreview(id, List.of(line));
        assertThat(out.guests())
                .anyMatch(x -> x.document() != null && x.document().endsWith("0123"));
        assertThat(mapper.writeValueAsString(out))
                .doesNotContain("000000000123", "document_number_encrypted");
    }

    @Test
    void cleanRoomSwapIsAtomicWithPaymentAndGuestValidation() {
        long other = room("102", type), id = confirmed(1), line = line(id);
        db.update("UPDATE rooms SET status='DIRTY' WHERE id=?", room);
        var p = workflow.checkinPreview(id, line);
        assertThat(p.lines().get(0).ready()).isFalse();
        assertThat(p.alternatives()).extracting(c -> c.id()).contains(other);
        assertThatThrownBy(
                        () ->
                                workflow.checkin(
                                        id,
                                        line,
                                        List.of(
                                                new StayService.Guest(
                                                        "Guest", "ADULT", "ID", null)),
                                        "Swap",
                                        code(""),
                                        "BANK_TRANSFER",
                                        new BigDecimal("880000"),
                                        other,
                                        p.projectedDebt(),
                                        p.version()))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
        assertThat(
                        db.one("SELECT room_id FROM room_allocations WHERE status='SCHEDULED'")
                                .id("room_id"))
                .isEqualTo(room);
        workflow.checkin(
                id,
                line,
                guests(),
                "Swap and arrive",
                code(""),
                "BANK_TRANSFER",
                new BigDecimal("880000"),
                other,
                p.projectedDebt(),
                p.version());
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", other).s("status"))
                .isEqualTo("OCCUPIED");
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("DIRTY");
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims WHERE room_id=?", other))
                .isEqualTo(1);
    }

    @Test
    void unverifiedTaskBlocksCleanStatusAndPriorityDoesNotDuplicateThenInspectionAllowsArrival() {
        long id = confirmed(1), line = line(id);
        long task = workflow.priority(id, line, "Guest waiting");
        assertThat(workflow.priority(id, line, "Guest still waiting")).isEqualTo(task);
        var p = workflow.checkinPreview(id, line);
        assertThat(p.lines().get(0).ready()).isFalse();
        assertThatThrownBy(
                        () ->
                                workflow.checkin(
                                        id,
                                        line,
                                        guests(),
                                        "Try",
                                        code(""),
                                        "BANK_TRANSFER",
                                        BigDecimal.ZERO,
                                        null,
                                        p.projectedDebt(),
                                        p.version()))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> workflow.clean(task, "verify", "Too early"))
                .isInstanceOf(AppException.class);
        workflow.clean(task, "start", "Cleaning");
        workflow.clean(task, "complete", "Finished");
        workflow.clean(task, "verify", "Inspected");
        assertThat(db.one("SELECT status FROM housekeeping_tasks WHERE id=?", task).s("status"))
                .isEqualTo("VERIFIED");
        assertThat(workflow.checkinPreview(id, line).lines().get(0).ready()).isTrue();
        arrival(id);
        assertThat(db.one("SELECT status FROM rooms WHERE id=?", room).s("status"))
                .isEqualTo("OCCUPIED");
    }

    @Test
    void maintenanceClaimAndMissingChildBirthRejectArrivalWithoutSideEffects() {
        long id = confirmed(1), line = line(id);
        long block =
                db.insert(
                        "room_blocks",
                        "room_id,block_type,from_date,to_date,reason,created_by",
                        room,
                        "MAINTENANCE",
                        today,
                        today.plusDays(1),
                        "Repair",
                        user);
        db.update("DELETE FROM room_night_claims WHERE room_id=?", room);
        db.update(
                "INSERT INTO room_night_claims(room_id,stay_date,room_block_id) VALUES(?,?,?)",
                room,
                today,
                block);
        var p = workflow.checkinPreview(id, line);
        assertThat(p.lines().get(0).ready()).isFalse();
        assertThatThrownBy(
                        () ->
                                workflow.checkin(
                                        id,
                                        line,
                                        guests(),
                                        "Try",
                                        code(""),
                                        "BANK_TRANSFER",
                                        new BigDecimal("880000"),
                                        null,
                                        p.projectedDebt(),
                                        p.version()))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM booking_guests")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
    }

    @Test
    void groupCheckoutRollsBackAllRoomsOnShortPaymentAndIssuesOneInvoiceOnRetry() {
        room("102", type);
        long id = create("PHONE", today, today.plusDays(1), 2);
        bookings.confirm(id, "Confirmed");
        var ids =
                db.rows("SELECT id FROM booking_rooms WHERE booking_id=? ORDER BY id", id).stream()
                        .map(r -> r.id("id"))
                        .toList();
        for (long line : ids) {
            var p = workflow.checkinPreview(id, line);
            workflow.checkin(
                    id,
                    line,
                    guests(),
                    "Arrived",
                    code(""),
                    "BANK_TRANSFER",
                    BigDecimal.ZERO,
                    null,
                    p.projectedDebt(),
                    p.version());
        }
        var p = workflow.checkoutPreview(id, ids);
        assertThat(p.projectedDebt()).isEqualByComparingTo("1760000");
        String key = code("");
        assertThatThrownBy(
                        () ->
                                workflow.checkout(
                                        id,
                                        ids,
                                        "Group leaving",
                                        key,
                                        "BANK_TRANSFER",
                                        new BigDecimal("1759999"),
                                        "COLLECT",
                                        null,
                                        p.projectedDebt(),
                                        p.version(),
                                        true,
                                        null,
                                        null))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM booking_rooms WHERE status='CHECKED_IN'"))
                .isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks")).isZero();
        var result =
                workflow.checkout(
                        id,
                        ids,
                        "Group leaving",
                        key,
                        "BANK_TRANSFER",
                        p.projectedDebt(),
                        "COLLECT",
                        null,
                        p.projectedDebt(),
                        p.version(),
                        true,
                        null,
                        null);
        assertThat(result.invoice()).isNotNull();
        workflow.checkout(
                id,
                ids,
                "Retry",
                key,
                "BANK_TRANSFER",
                p.projectedDebt(),
                "COLLECT",
                null,
                p.projectedDebt(),
                p.version(),
                true,
                null,
                null);
        assertThat(db.count("SELECT COUNT(*) FROM invoices")).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isEqualTo(1);
        assertThat(db.count("SELECT COUNT(*) FROM housekeeping_tasks WHERE status='PENDING'"))
                .isEqualTo(2);
        assertThat(db.count("SELECT COUNT(*) FROM stay_guests WHERE departed_at IS NULL")).isZero();
        assertThat(db.one("SELECT status FROM bookings WHERE id=?", id).s("status"))
                .isEqualTo("CHECKED_OUT");
    }

    @Test
    void latePercentageFeeUsesAcceptedNetNightAndTaxThenCashRequiresOwnShift() {
        long id = confirmed(1), line = line(id);
        arrival(id);
        surcharge("LATE_CHECKOUT", "PERCENT_NIGHT", "50");
        db.update(
                "UPDATE booking_rooms SET expected_check_in_at=?,expected_check_out_at=? WHERE"
                        + " id=?",
                ts(Instant.now().minusSeconds(86400)),
                ts(Instant.now().minusSeconds(75 * 60)),
                line);
        var p = workflow.checkoutPreview(id, List.of(line));
        assertThat(p.lines().get(0).preview().surcharge().amount()).isEqualByComparingTo("400000");
        assertThat(p.projectedDebt()).isEqualByComparingTo("1320000");
        assertThatThrownBy(
                        () ->
                                workflow.checkout(
                                        id,
                                        List.of(line),
                                        "Leave",
                                        code(""),
                                        "CASH",
                                        p.projectedDebt(),
                                        "COLLECT",
                                        null,
                                        p.projectedDebt(),
                                        p.version(),
                                        false,
                                        null,
                                        null))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM booking_charges WHERE kind='SURCHARGE'"))
                .isZero();
        payments.open(BigDecimal.ZERO);
        workflow.checkout(
                id,
                List.of(line),
                "Leave",
                code(""),
                "CASH",
                p.projectedDebt(),
                "COLLECT",
                null,
                p.projectedDebt(),
                p.version(),
                false,
                null,
                null);
        assertThat(db.one("SELECT work_session_id FROM payment_transactions").id("work_session_id"))
                .isNotNull();
        assertThat(ledger.balance(id).debt()).isEqualByComparingTo("0");
    }

    @Test
    void earlyDepartureRefundNeedsPermissionAndParentPaymentAndPdfContainsQrAndSignatureAreas()
            throws Exception {
        long id = confirmed(3), line = line(id);
        arrival(id);
        long parent =
                payments.collect(
                        id,
                        code(""),
                        "PAYMENT",
                        "BANK_TRANSFER",
                        new BigDecimal("2640000"),
                        "Prepaid");
        var p = workflow.checkoutPreview(id, List.of(line));
        assertThat(p.refundDue()).isEqualByComparingTo("1760000");
        assertThatThrownBy(
                        () ->
                                workflow.checkout(
                                        id,
                                        List.of(line),
                                        "Leave early",
                                        code(""),
                                        "BANK_TRANSFER",
                                        p.refundDue(),
                                        "REFUND",
                                        parent,
                                        p.projectedDebt(),
                                        p.version(),
                                        true,
                                        null,
                                        null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(db.one("SELECT status FROM booking_rooms WHERE id=?", line).s("status"))
                .isEqualTo("CHECKED_IN");
        admin();
        var result =
                workflow.checkout(
                        id,
                        List.of(line),
                        "Approved early refund",
                        code(""),
                        "BANK_TRANSFER",
                        p.refundDue(),
                        "REFUND",
                        parent,
                        p.projectedDebt(),
                        p.version(),
                        true,
                        "Hà Nội",
                        null);
        assertThat(ledger.balance(id).debt()).isEqualByComparingTo("0");
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE"
                                        + " inventory_state='RELEASED'"))
                .isEqualTo(2);
        assertThat(
                        db.one(
                                        "SELECT parent_payment_id FROM payment_transactions WHERE"
                                                + " kind='REFUND'")
                                .id("parent_payment_id"))
                .isEqualTo(parent);
        byte[] pdf = invoices.download(result.invoice()).data();
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("Khách hàng", "Đại diện khách sạn", "Hà Nội", "QR định danh");
            boolean found = false;
            for (var name : doc.getPage(0).getResources().getXObjectNames()) {
                var image = doc.getPage(0).getResources().getXObject(name);
                if (image instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) {
                    var bytes = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(img.getImage(), "png", bytes);
                    assertThat(qr.read(bytes.toByteArray())).startsWith("HOTEL-INVOICE:");
                    found = true;
                }
            }
            assertThat(found).isTrue();
            var preview =
                    new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 110);
            javax.imageio.ImageIO.write(
                    preview,
                    "png",
                    java.nio.file.Path.of("target/stay-invoice-preview.png").toFile());
            java.nio.file.Files.write(
                    java.nio.file.Path.of("target/stay-invoice-preview.pdf"), pdf);
        }
    }

    @Test
    void reviewedDebtAndVersionRejectChangedLedgerAndChildBirthIsRequired() {
        var f =
                new Create(
                        code(""),
                        null,
                        "Guest",
                        "0901234567",
                        null,
                        "PHONE",
                        null,
                        null,
                        List.of(new RoomInput(type, today, today.plusDays(1), 1, 1)));
        long id = bookings.create(f, bookings.preview(f).totalAmount()), line = line(id);
        bookings.confirm(id, "Confirmed");
        var p = workflow.checkinPreview(id, line);
        var people = new ArrayList<>(guests());
        people.add(new StayService.Guest("Child", "CHILD", null, null));
        assertThatThrownBy(
                        () ->
                                workflow.checkin(
                                        id,
                                        line,
                                        people,
                                        "Arrive",
                                        code(""),
                                        "BANK_TRANSFER",
                                        new BigDecimal("880000"),
                                        null,
                                        p.projectedDebt(),
                                        p.version()))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
        people.set(
                1, new StayService.Guest("Child", "CHILD", null, null, today.minusYears(8), null));
        assertThatThrownBy(
                        () ->
                                workflow.checkin(
                                        id,
                                        line,
                                        people,
                                        "Arrive",
                                        code(""),
                                        "BANK_TRANSFER",
                                        BigDecimal.ZERO,
                                        null,
                                        p.projectedDebt(),
                                        p.version() + 1))
                .isInstanceOf(AppException.class);
        payments.collect(
                id, code(""), "PAYMENT", "BANK_TRANSFER", new BigDecimal("100000"), "Paid");
        assertThatThrownBy(
                        () ->
                                workflow.checkin(
                                        id,
                                        line,
                                        people,
                                        "Arrive",
                                        code(""),
                                        "BANK_TRANSFER",
                                        BigDecimal.ZERO,
                                        null,
                                        p.projectedDebt(),
                                        p.version()))
                .isInstanceOf(AppException.class);
    }

    @Test
    void qrTokensAreHashedExpireRotateAndDecodeFromImageWithoutExposingDocuments()
            throws Exception {
        long id = confirmed(1);
        var first = lookup.issue(id);
        assertThat(lookup.find(first.payload(), "QR")).extracting(x -> x.id()).containsExactly(id);
        assertThat(db.one("SELECT token_hash FROM booking_qr_tokens").s("token_hash"))
                .doesNotContain(first.payload().substring(14));
        byte[] png =
                Base64.getDecoder().decode(first.image().substring(first.image().indexOf(',') + 1));
        mvc.perform(
                        multipart("/admin/receptionist/stays/qr-image")
                                .file(
                                        new org.springframework.mock.web.MockMultipartFile(
                                                "image", "qr.png", "image/png", png))
                                .session(session)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        authenticate();
        var second = lookup.issue(id);
        assertThatThrownBy(() -> lookup.find(first.payload(), "QR"))
                .isInstanceOf(AppException.class);
        db.update(
                "UPDATE booking_qr_tokens SET expires_at=? WHERE revoked_at IS NULL",
                ts(Instant.now().minusSeconds(1)));
        assertThatThrownBy(() -> lookup.find(second.payload(), "QR"))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> lookup.image(new byte[] {1, 2, 3}))
                .isInstanceOf(AppException.class);
        assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='BOOKING_QR_SCAN'"))
                .isEqualTo(2);
    }

    @Test
    void newHtmlAndJsonEndpointsRequireCsrfAndReturnMaskedRegistration() throws Exception {
        long id = confirmed(1), line = line(id);
        mvc.perform(get("/admin/receptionist/stays").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stay-procedure")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stay-lookup")));
        mvc.perform(
                        get("/admin/bookings/{id}/checkin-preview", id)
                                .param("line", Long.toString(line))
                                .session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.lines[0].ready").value(true))
                .andExpect(jsonPath("$.contact.name").value("Nguyễn Minh"));
        mvc.perform(
                        post("/admin/receptionist/stays/lookup")
                                .session(session)
                                .contentType("application/json")
                                .content("{\"q\":\"0901234567\",\"mode\":\"CONTACT\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/receptionist/stays/lookup")
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"q\":\"0901234567\",\"mode\":\"CONTACT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        authenticate();
        var bill = workflow.checkinPreview(id, line);
        var payload =
                new com.example.hotelmanagement.web.admin.controller.ReceptionStayController
                        .Checkin(
                        line,
                        guests(),
                        "Arrived",
                        code(""),
                        "BANK_TRANSFER",
                        BigDecimal.ZERO,
                        null,
                        bill.projectedDebt(),
                        bill.version());
        mvc.perform(
                        post("/admin/receptionist/stays/{id}/checkin", id)
                                .session(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking").value(id));
        String result =
                mvc.perform(
                                get("/admin/bookings/{id}/checkout-preview", id)
                                        .param("line", Long.toString(line))
                                        .session(session))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.guests[0].name").value("Nguyễn Minh"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(result).doesNotContain("000000000123").contains("0123");
        authenticate();
        var checkoutBill = workflow.checkoutPreview(id, List.of(line));
        var checkoutPayload =
                new com.example.hotelmanagement.web.admin.controller.ReceptionStayController
                        .Checkout(
                        List.of(line),
                        "Departed",
                        code(""),
                        "BANK_TRANSFER",
                        checkoutBill.projectedDebt(),
                        "COLLECT",
                        null,
                        checkoutBill.projectedDebt(),
                        checkoutBill.version(),
                        true,
                        null,
                        null);
        String receipt =
                mvc.perform(
                                post("/admin/receptionist/stays/{id}/checkout", id)
                                        .session(session)
                                        .with(csrf())
                                        .contentType("application/json")
                                        .content(mapper.writeValueAsString(checkoutPayload)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.invoice").isNumber())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long invoice = mapper.readTree(receipt).get("invoice").longValue();
        mvc.perform(get("/admin/invoices/{id}/pdf", invoice).session(session))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
    }
}
