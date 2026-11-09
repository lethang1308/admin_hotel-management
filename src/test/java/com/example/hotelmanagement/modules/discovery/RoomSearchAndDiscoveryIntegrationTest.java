package com.example.hotelmanagement.modules.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

@SpringBootTest(
        properties = {
            "app.hotel.inventory-days=90",
            "spring.datasource.url=${DISCOVERY_TEST_DB_URL:jdbc:h2:mem:hotel_discovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE}"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomSearchAndDiscoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperationsStore db;
    @Autowired HotelInventoryCoordinator inventory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;

    private static final String HOTEL = "/api/v1/hotels";
    private static final String FAVORITES = "/api/v1/users/wishlist";
    private long std, dlx, vip, floor;
    private LocalDate today, from, until;

    @BeforeEach
    void setup() {
        CmsTestData.clear(jdbc);
        today = inventory.today();
        from = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        until = from.plusDays(2);
        std = type("STD", 1000000, 2, 0, 2, false);
        dlx = type("DLX", 2000000, 3, 2, 4, true);
        vip = type("VIP", 4000000, 4, 2, 6, true);
        floor =
                db.insert(
                        "floors",
                        "code,name,area_name,floor_number",
                        "DISCOVERY_F1",
                        "Floor 1",
                        "A",
                        1);
        room("101", std, today);
        room("201", dlx, today);
        room("202", dlx, today);
        room("301", vip, today);
        reconcile();
        db.update(
                "UPDATE room_type_inventory SET held_rooms=1 WHERE room_type_id=? AND stay_date=?",
                vip,
                from.plusDays(1));
        db.update(
                "UPDATE hotel_settings SET name='Discovery hotel',address='Đà Nẵng, Việt"
                    + " Nam',default_tax_percent=10 WHERE id=1");
        long rule =
                db.insert(
                        "rate_rules",
                        "code,name,kind,start_date,end_date,weekday_mask,value_type,value",
                        "SATURDAY",
                        "Saturday rate",
                        "NIGHT_OVERRIDE",
                        today,
                        today.plusDays(90),
                        32,
                        "FIXED",
                        3000000);
        db.update(
                "INSERT INTO rate_rule_room_types(rate_rule_id,room_type_id) VALUES(?,?)",
                rule,
                dlx);
        db.update(
                """
                INSERT INTO room_type_amenities(room_type_id,amenity_id)
                SELECT ?,id FROM amenities WHERE code IN ('WIFI','BATHTUB','BALCONY')
                """,
                dlx);
        db.update(
                "INSERT INTO room_type_amenities(room_type_id,amenity_id) SELECT ?,id FROM"
                    + " amenities WHERE code='WIFI'",
                std);
        db.insert(
                "room_type_images",
                "room_type_id,image_url,sort_order",
                dlx,
                "/media/hotel/deluxe-2.jpg",
                2);
        db.insert(
                "room_type_images",
                "room_type_id,image_url,sort_order",
                dlx,
                "/media/hotel/deluxe-1.jpg",
                1);
    }

    @Test
    void publicSearchUsesFullStayInventoryAndPriceBeforePagination() throws Exception {
        mvc.perform(search().param("size", "1").param("sortBy", "PRICE_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(dlx))
                .andExpect(jsonPath("$.data.items[0].availableRooms").value(2))
                .andExpect(jsonPath("$.data.items[0].price").value(2500000))
                .andExpect(jsonPath("$.data.items[0].totalStayPrice").value(5500000))
                .andExpect(jsonPath("$.data.items[0].isSoldOut").value(false));
        mvc.perform(search().param("page", "1").param("size", "1").param("sortBy", "PRICE_DESC"))
                .andExpect(jsonPath("$.data.items[0].id").value(std));
        mvc.perform(search().param("roomsCount", "2").param("adults", "4").param("children", "2"))
                .andExpect(jsonPath("$.data.items[0].id").value(dlx))
                .andExpect(jsonPath("$.data.items[0].totalStayPrice").value(11000000));
    }

    @Test
    void filtersUseEffectivePriceRoomCodesAndAllSelectedAmenities() throws Exception {
        mvc.perform(
                        search().param("city", "Da Nang")
                                .param("type", "DELUXE,VIP")
                                .param("amenities", "wifi,bathtub,balcony")
                                .param("adults", "3")
                                .param("children", "1")
                                .param("minPrice", "2400000")
                                .param("maxPrice", "2600000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("DLX"));
        mvc.perform(search().param("amenities", "wifi,spa"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(search().param("type", "DLX").param("maxPrice", "2100000"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(search().param("children", "3"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void availabilityRequiresTheSamePhysicalRoomForTheWholeStay() throws Exception {
        db.update("UPDATE rooms SET sell_until=? WHERE room_number='101'", from.plusDays(1));
        room("102", std, from.plusDays(1));
        reconcile();
        mvc.perform(search().param("type", "STANDARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(
                        get(HOTEL + "/" + std + "/calendar")
                                .param("from", from.toString())
                                .param("to", from.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].remainingRooms").value(1))
                .andExpect(jsonPath("$.data.days[1].remainingRooms").value(1));
    }

    @Test
    void detailAndCalendarReturnRealRatesAndUnavailableNights() throws Exception {
        mvc.perform(
                        get(HOTEL + "/" + dlx + "/info")
                                .param("startDate", from.toString())
                                .param("endDate", until.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hotel.name").value("Discovery hotel"))
                .andExpect(jsonPath("$.data.rooms[0].photos[0]").value("/media/hotel/deluxe-1.jpg"))
                .andExpect(jsonPath("$.data.rooms[0].roomSubtotal").value(5000000))
                .andExpect(jsonPath("$.data.rooms[0].maxOccupancy").value(4))
                .andExpect(jsonPath("$.data.policy.depositType").value("NONE"));
        mvc.perform(
                        get(HOTEL + "/" + dlx + "/calendar")
                                .param("from", from.toString())
                                .param("to", from.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days.length()").value(2))
                .andExpect(jsonPath("$.data.days[0].price").value(3000000))
                .andExpect(jsonPath("$.data.days[0].isWeekend").value(true))
                .andExpect(jsonPath("$.data.days[1].price").value(2000000));
        mvc.perform(
                        get(HOTEL + "/" + vip + "/calendar")
                                .param("month", YearMonth.from(from.plusDays(1)).toString()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.data.days["
                                                + (from.plusDays(1).getDayOfMonth() - 1)
                                                + "].available")
                                .value(false));
        mvc.perform(
                        get(HOTEL + "/" + vip + "/info")
                                .param("startDate", from.toString())
                                .param("endDate", until.toString()))
                .andExpect(jsonPath("$.data.rooms[0].isSoldOut").value(true));
    }

    @Test
    void publishedReviewsAndOffersOnlyExposePublicData() throws Exception {
        var account = register("reviewer@example.test", "0912345678");
        long customer = db.one("SELECT id FROM customers WHERE user_id=?", account.id()).id("id");
        var h = db.one("SELECT * FROM hotel_settings WHERE id=1");
        long booking =
                db.insert(
                        "bookings",
                        "booking_code,customer_id,source,status,contact_name,contact_phone,deposit_policy_id,cancellation_policy_id,policy_snapshot",
                        "DISCOVERY_REVIEW",
                        customer,
                        "PHONE",
                        "CHECKED_OUT",
                        "Private contact",
                        "0912345678",
                        h.id("default_deposit_policy_id"),
                        h.id("default_cancellation_policy_id"),
                        "{}");
        for (int i = 1; i <= 2; i++) {
            long line =
                    db.insert(
                            "booking_rooms",
                            "booking_id,line_number,room_type_id,room_type_name_snapshot,check_in_date,check_out_date,expected_check_in_at,expected_check_out_at,adults,status",
                            booking,
                            i,
                            dlx,
                            "Deluxe",
                            today.minusDays(2),
                            today.minusDays(1),
                            today.minusDays(2).atTime(14, 0),
                            today.minusDays(1).atTime(12, 0),
                            2,
                            "CHECKED_OUT");
            db.insert(
                    "reviews",
                    "booking_id,booking_room_id,customer_id,rating,comment,status,reply",
                    booking,
                    line,
                    customer,
                    i == 1 ? 5 : 1,
                    i == 1 ? "Public review" : "Hidden review",
                    i == 1 ? "PUBLISHED" : "HIDDEN",
                    "Hotel reply");
        }
        var now = Instant.now();
        db.insert(
                "vouchers",
                "code,name,discount_type,discount_value,starts_at,expires_at,created_by",
                "DISCOVERY_OFFER",
                "Offer",
                "PERCENT",
                15,
                OperationsStore.ts(now.minusSeconds(60)),
                OperationsStore.ts(now.plusSeconds(3600)),
                account.id());
        mvc.perform(get(HOTEL + "/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offers[0].code").value("DISCOVERY_OFFER"))
                .andExpect(jsonPath("$.data.bankAccountNumber").doesNotExist());
        mvc.perform(get(HOTEL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(dlx));
        mvc.perform(get(HOTEL + "/" + dlx + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.averageRating").value(5))
                .andExpect(jsonPath("$.data.content[0].comment").value("Public review"))
                .andExpect(jsonPath("$.data.content[0].guestName").value("An N."))
                .andExpect(jsonPath("$.data.content[0].reply").value("Hotel reply"))
                .andExpect(jsonPath("$.data.content[0].customerId").doesNotExist());
    }

    @Test
    void wishlistIsOwnedIdempotentAndPrivate() throws Exception {
        var a = register("first@example.test", "0912345678");
        var b = register("second@example.test", "0987654321");
        mvc.perform(get(FAVORITES)).andExpect(status().isUnauthorized());
        for (int i = 0; i < 2; i++)
            mvc.perform(auth(post(FAVORITES + "/" + dlx), a)).andExpect(status().isOk());
        assertThat(db.count("SELECT COUNT(*) FROM favorites")).isEqualTo(1);
        mvc.perform(auth(get(FAVORITES + "/ids"), a)).andExpect(jsonPath("$.data[0]").value(dlx));
        mvc.perform(auth(get(FAVORITES), a)).andExpect(jsonPath("$.data.items[0].id").value(dlx));
        mvc.perform(auth(delete(FAVORITES + "/" + dlx), b)).andExpect(status().isOk());
        mvc.perform(auth(get(FAVORITES + "/ids"), b))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(db.count("SELECT COUNT(*) FROM favorites")).isEqualTo(1);
        mvc.perform(auth(delete(FAVORITES + "/" + dlx), a)).andExpect(status().isOk());
        mvc.perform(auth(get(FAVORITES + "/ids"), a))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void invalidDatesMissingInventoryAndInactiveRoomsAreHandled() throws Exception {
        mvc.perform(
                        get(HOTEL + "/search")
                                .param("checkIn", until.toString())
                                .param("checkOut", from.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(search().param("roomsCount", "3").param("adults", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(search().param("minPrice", "1000000").param("maxPrice", "10"))
                .andExpect(status().isBadRequest());
        db.update(
                "DELETE FROM room_type_inventory WHERE room_type_id=? AND stay_date=?",
                dlx,
                from.plusDays(1));
        mvc.perform(search().param("type", "DELUXE"))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        db.update("UPDATE room_types SET is_active=FALSE WHERE id=?", dlx);
        mvc.perform(get(HOTEL + "/" + dlx + "/info")).andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder search() {
        return get(HOTEL + "/search")
                .param("checkIn", from.toString())
                .param("checkOut", until.toString());
    }

    private long type(
            String code, int price, int adults, int children, int occupancy, boolean featured) {
        db.update(
                """
                UPDATE room_types SET base_price=?,max_adults=?,max_children=?,max_occupancy=?,
                is_featured=?,is_active=TRUE WHERE code=?
                """,
                price,
                adults,
                children,
                occupancy,
                featured,
                code);
        return db.one("SELECT id FROM room_types WHERE code=?", code).id("id");
    }

    private void room(String number, long type, LocalDate from) {
        db.insert(
                "rooms", "room_number,room_type_id,floor_id,sell_from", number, type, floor, from);
    }

    private void reconcile() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            inventory.lock();
                            inventory.reconcile(List.of(std, dlx, vip));
                        });
    }

    private record Account(long id, String token) {}

    private Account register(String email, String phone) throws Exception {
        var response =
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "fullName",
                                                                "Nguyễn Văn An",
                                                                "email",
                                                                email,
                                                                "phone",
                                                                phone,
                                                                "password",
                                                                "DiscoveryPass123"))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        var data = json.readTree(response.getContentAsString()).path("data");
        return new Account(data.at("/user/id").asLong(), data.path("accessToken").asString());
    }

    private static MockHttpServletRequestBuilder auth(
            MockHttpServletRequestBuilder request, Account account) {
        return request.header("Authorization", "Bearer " + account.token());
    }
}
