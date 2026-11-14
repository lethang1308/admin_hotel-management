package com.example.hotelmanagement.modules.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.support.CmsTestData;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        properties = {
            "app.hotel.inventory-days=45",
            "app.booking.sandbox-enabled=true",
            "spring.datasource.url=${ONLINE_TEST_DB_URL:jdbc:h2:mem:online_booking;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE}"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnlineBookingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperationsStore db;
    @Autowired HotelInventoryCoordinator inventory;
    @Autowired PlatformTransactionManager tx;
    @Autowired BookingService bookings;
    @Autowired JsonMapper json;
    String buyer, other;
    long type, alternate;
    LocalDate arrival;

    @BeforeEach
    void setup() throws Exception {
        CmsTestData.clear(jdbc);
        buyer = register("booker@example.test", "0912345678");
        other = register("other@example.test", "0912345679");
        arrival = inventory.today().plusDays(5);
        db.update(
                "UPDATE room_types SET"
                    + " base_price=1000000,max_adults=2,max_children=1,max_occupancy=3,is_active=TRUE"
                    + " WHERE code IN ('STD','DLX')");
        type = db.one("SELECT id FROM room_types WHERE code='STD'").id("id");
        alternate = db.one("SELECT id FROM room_types WHERE code='DLX'").id("id");
        long floor =
                db.insert(
                        "floors",
                        "code,name,area_name,floor_number",
                        "ON_F1",
                        "First floor",
                        "A",
                        1);
        db.insert(
                "rooms",
                "room_number,room_type_id,floor_id,sell_from",
                "101",
                type,
                floor,
                arrival.minusDays(1));
        db.insert(
                "rooms",
                "room_number,room_type_id,floor_id,sell_from",
                "201",
                alternate,
                floor,
                arrival.minusDays(1));
        new TransactionTemplate(tx)
                .executeWithoutResult(
                        s -> {
                            inventory.lock();
                            inventory.reconcile(List.of(type, alternate));
                        });
        long policy =
                db.insert(
                        "deposit_policies",
                        "code,name,amount_type,amount_value",
                        "TEST30",
                        "30% deposit",
                        "PERCENT",
                        30);
        db.update(
                "UPDATE hotel_settings SET default_deposit_policy_id=?,default_tax_percent=10 WHERE"
                        + " id=1",
                policy);
        long actor = db.one("SELECT id FROM users WHERE email=?", "booker@example.test").id("id");
        db.insert(
                "vouchers",
                "code,name,discount_type,discount_value,starts_at,expires_at,max_uses,created_by",
                "WEB10",
                "Web offer",
                "PERCENT",
                10,
                OperationsStore.ts(Instant.now().minus(Duration.ofDays(1))),
                OperationsStore.ts(Instant.now().plus(Duration.ofDays(1))),
                1,
                actor);
    }

    @Test
    void holdsPricesAndQuotasThenConfirmsAndRecordsRefundDue() throws Exception {
        var cart = cart(true);
        var quote =
                data(
                        postAs(buyer, "/bookings/quote", cart)
                                .andExpect(jsonPath("$.data.totalAmount").value(4400000)));
        long booking = hold(buyer, cart, quote);
        assertThat(hold(buyer, cart, quote)).isEqualTo(booking);
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isEqualTo(4);
        String path = "/bookings/" + booking;
        getAs(other, path).andExpect(status().isNotFound());
        postAs(other, path + "/addGuests", Map.of()).andExpect(status().isNotFound());
        postAs(other, "/bookings/quote", cart).andExpect(status().isConflict());
        long guest =
                data(postAs(
                                        buyer,
                                        "/users/guests",
                                        Map.of(
                                                "name",
                                                "Guest child",
                                                "guestType",
                                                "CHILD",
                                                "documentType",
                                                "PASSPORT",
                                                "documentNumber",
                                                "TEST1234"))
                                .andExpect(status().isCreated()))
                        .path("id")
                        .asLong();
        mvc.perform(
                        delete("/api/v1/users/guests/" + guest)
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        postAs(
                        buyer,
                        path + "/addGuests",
                        Map.of("guestIds", List.of(guest), "specialRequests", "Late arrival"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guests[1].guestType").value("CHILD"));
        var snapshot =
                db.one(
                        "SELECT * FROM booking_guests WHERE booking_id=? AND guest_type='CHILD'",
                        booking);
        assertThat(snapshot.s("document_number_encrypted"))
                .startsWith("v1:")
                .doesNotContain("TEST1234");
        postAs(buyer, path + "/addGuests", Map.of("retainedGuestIds", List.of(snapshot.id("id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guests[1].documentNumber").doesNotExist());
        assertThat(
                        db.one(
                                        "SELECT * FROM booking_guests WHERE booking_id=? AND"
                                                + " guest_type='CHILD'",
                                        booking)
                                .s("document_number_hash"))
                .isEqualTo(snapshot.s("document_number_hash"));
        long firstPay = begin(path, 4400000, 1320000);
        postAs(buyer, "/vouchers/apply", Map.of("bookingId", booking, "voucherCode", "WEB10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(3960000));
        postAs(buyer, "/vouchers/apply", Map.of("bookingId", booking, "voucherCode", "INVALID"))
                .andExpect(status().isBadRequest());
        process(buyer, firstPay, "SUCCESS").andExpect(status().isConflict());
        postAs(buyer, path + "/payments", payment(4400000, 1320000))
                .andExpect(status().isConflict());
        long failed = begin(path, 3960000, 1188000);
        process(other, failed, "SUCCESS").andExpect(status().isNotFound());
        process(buyer, failed, "FAIL")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        long paid = begin(path, 3960000, 1188000);
        assertThat(paid).isNotEqualTo(failed);
        process(buyer, paid, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingStatus").value("CONFIRMED"));
        process(buyer, paid, "SUCCESS").andExpect(status().isOk());
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM payment_transactions WHERE booking_id=? AND"
                                        + " status='SUCCEEDED'",
                                booking))
                .isOne();
        getAs(buyer, path)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrImage").isString());
        getAs(buyer, "/users/myBookings?status=CONFIRMED")
                .andExpect(jsonPath("$.data.totalElements").value(1));
        assertThat(
                        db.count(
                                "SELECT COUNT(*) FROM guest_messages WHERE booking_id=? AND"
                                        + " message_type='CONFIRMATION'",
                                booking))
                .isOne();
        postAs(other, path + "/cancel", Map.of("reason", "Unauthorized"))
                .andExpect(status().isNotFound());
        postAs(buyer, path + "/cancel", Map.of("reason", "Changed plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.refundAmount").value(1188000))
                .andExpect(jsonPath("$.data.qrImage").doesNotExist());
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isZero();
        assertThat(
                        db.one("SELECT status FROM voucher_redemptions WHERE booking_id=?", booking)
                                .s("status"))
                .isEqualTo("RELEASED");
    }

    @Test
    void expiryReleasesInventoryAndPreventsLatePayment() throws Exception {
        var input = cart(false);
        var quote = data(postAs(buyer, "/bookings/quote", input));
        long id = hold(buyer, input, quote);
        long p = begin("/bookings/" + id, 2200000, 660000);
        db.update(
                "UPDATE bookings SET hold_expires_at=? WHERE id=?",
                OperationsStore.ts(Instant.now().minusSeconds(1)),
                id);
        getAs(buyer, "/bookings/" + id + "/status")
                .andExpect(jsonPath("$.data.bookingStatus").value("EXPIRED"));
        process(buyer, p, "SUCCESS").andExpect(status().isGone());
        bookings.expire(id);
        assertThat(db.count("SELECT COUNT(*) FROM room_night_claims")).isZero();
        assertThat(db.one("SELECT status FROM payment_transactions WHERE id=?", p).s("status"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void lastRoomCanOnlyBeHeldOnceAndZeroDepositConfirmsWithoutTransaction() throws Exception {
        db.update(
                "UPDATE hotel_settings SET default_deposit_policy_id=(SELECT id FROM"
                        + " deposit_policies WHERE code='CMS_NO_DEPOSIT') WHERE id=1");
        var input = cart(false);
        var quote = data(postAs(buyer, "/bookings/quote", input));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first =
                    pool.submit(
                            () -> init(buyer, input, quote).andReturn().getResponse().getStatus());
            var second =
                    pool.submit(
                            () ->
                                    init(other, cart(false), quote)
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            int a = first.get(20, TimeUnit.SECONDS), b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a, b)).containsExactlyInAnyOrder(200, 409);
            long id = db.one("SELECT id FROM bookings").id("id");
            postAs(a == 200 ? buyer : other, "/bookings/" + id + "/payments", payment(2200000, 0))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bookingStatus").value("CONFIRMED"));
            assertThat(db.count("SELECT COUNT(*) FROM payment_transactions")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationUsesFrozenFeesAndKeepsCustomerDeadline() throws Exception {
        long policy =
                db.insert(
                        "cancellation_policies",
                        "code,name,customer_cancel_deadline_hours",
                        "WEB_FEE",
                        "Snapshot rules",
                        24);
        db.insert(
                "cancellation_policy_rules",
                "cancellation_policy_id,min_hours_before_checkin,fee_type,fee_value",
                policy,
                0,
                "FIXED",
                100000);
        db.insert(
                "cancellation_policy_rules",
                "cancellation_policy_id,min_hours_before_checkin,fee_type,fee_value",
                policy,
                240,
                "NONE",
                0);
        db.update("UPDATE hotel_settings SET default_cancellation_policy_id=? WHERE id=1", policy);
        var input = cart(false);
        long id = hold(buyer, input, data(postAs(buyer, "/bookings/quote", input)));
        String path = "/bookings/" + id;
        process(buyer, begin(path, 2200000, 660000), "SUCCESS").andExpect(status().isOk());
        getAs(buyer, "/users/myBookings?status=UPCOMING&sort=PRICE_DESC&page=0&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(id))
                .andExpect(jsonPath("$.data.stats.confirmed").value(1));
        db.update(
                "UPDATE cancellation_policy_rules SET fee_value=200000 WHERE"
                    + " cancellation_policy_id=? AND min_hours_before_checkin=0",
                policy);
        getAs(buyer, path).andExpect(jsonPath("$.data.cancellation.fee").value(100000));
        postAs(buyer, path + "/cancel", Map.of("reason", "Changed plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.refundAmount").value(560000));
        assertThat(
                        db.one(
                                        "SELECT cancellation_fee FROM booking_cancellations WHERE"
                                            + " booking_id=?",
                                        id)
                                .money("cancellation_fee"))
                .isEqualByComparingTo("100000");
    }

    private String register(String email, String phone) throws Exception {
        return data(postAs(
                                null,
                                "/auth/register",
                                Map.of(
                                        "fullName",
                                        "Online Guest",
                                        "email",
                                        email,
                                        "phone",
                                        phone,
                                        "password",
                                        "OnlinePass123"))
                        .andExpect(status().isCreated()))
                .path("accessToken")
                .asString();
    }

    private Map<String, Object> cart(boolean mixed) {
        var first = room(type);
        return Map.of(
                "rooms",
                mixed ? List.of(first, room(alternate)) : List.of(first),
                "requestKey",
                UUID.randomUUID().toString());
    }

    private Map<String, Object> room(long roomType) {
        return Map.of(
                "typeId",
                roomType,
                "from",
                arrival,
                "to",
                arrival.plusDays(2),
                "adults",
                1,
                "children",
                1);
    }

    private ResultActions init(String token, Map<String, Object> input, JsonNode quote)
            throws Exception {
        var body = new HashMap<>(input);
        body.put("expectedTotal", quote.path("totalAmount").decimalValue());
        return postAs(token, "/bookings/init", body);
    }

    private long hold(String token, Map<String, Object> input, JsonNode quote) throws Exception {
        return data(init(token, input, quote)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.bookingStatus").value("PENDING")))
                .path("id")
                .asLong();
    }

    private Map<String, Object> payment(int total, int deposit) {
        return Map.of("provider", "SANDBOX", "expectedTotal", total, "expectedDeposit", deposit);
    }

    private long begin(String path, int total, int deposit) throws Exception {
        return data(postAs(buyer, path + "/payments", payment(total, deposit))
                        .andExpect(status().isOk()))
                .path("transactionId")
                .asLong();
    }

    private ResultActions process(String token, long id, String action) throws Exception {
        return postAs(
                token, "/payments/sandbox/process", Map.of("transactionId", id, "action", action));
    }

    private ResultActions postAs(String token, String path, Object input) throws Exception {
        var req =
                post("/api/v1" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token));
    }

    private JsonNode data(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString()).path("data");
    }
}
