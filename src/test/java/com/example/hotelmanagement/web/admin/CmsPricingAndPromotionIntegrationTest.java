package com.example.hotelmanagement.web.admin;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.policy.dto.*;
import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.policy.service.*;
import com.example.hotelmanagement.modules.pricing.dto.*;
import com.example.hotelmanagement.modules.pricing.entity.*;
import com.example.hotelmanagement.modules.pricing.service.*;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.modules.voucher.dto.*;
import com.example.hotelmanagement.modules.voucher.entity.*;
import com.example.hotelmanagement.modules.voucher.service.*;
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

import java.math.*;
import java.time.*;
import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmsPricingAndPromotionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired RateRuleService rates;
    @Autowired VoucherService vouchers;
    @Autowired DepositPolicyService deposits;
    @Autowired CancellationPolicyService cancellations;
    @Autowired SurchargePolicyService surcharges;
    @Autowired RoomPricingEngineService engine;
    @Autowired PricingSupport support;
    @Autowired HotelInventoryCoordinator guard;
    MockHttpSession admin;
    Long adminId, typeId, customerId;

    @BeforeEach
    void setup() throws Exception {
        CmsTestData.clear(jdbc);
        adminId =
                users.createUser(
                                CreateUserRequest.builder()
                                        .fullName("Pricing Admin")
                                        .email("pricing.admin@example.test")
                                        .password("InitialPass123")
                                        .roles(Set.of("ADMIN"))
                                        .build())
                        .getId();
        Long uid =
                users.createUser(
                                CreateUserRequest.builder()
                                        .fullName("Voucher Customer")
                                        .email("pricing.customer@example.test")
                                        .password("InitialPass123")
                                        .roles(Set.of("USER"))
                                        .build())
                        .getId();
        customerId =
                jdbc.queryForObject("SELECT id FROM customers WHERE user_id=?", Long.class, uid);
        typeId = jdbc.queryForObject("SELECT id FROM room_types WHERE code='STD'", Long.class);
        jdbc.update("UPDATE room_types SET base_price=800000,is_active=true WHERE id=?", typeId);
        admin = login("pricing.admin@example.test");
        authenticate();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.setContext(
                (SecurityContext) admin.getAttribute("SPRING_SECURITY_CONTEXT"));
    }

    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/admin/login")
                                        .with(csrf())
                                        .param("email", email)
                                        .param("password", "InitialPass123"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    private RateRuleForm rate() {
        var f = new RateRuleForm();
        f.setCode("TEST_WEEKEND");
        f.setName("Weekend");
        f.setStartDate(guard.today());
        f.setEndDate(guard.today().plusMonths(1));
        f.setValue(new BigDecimal("1000000"));
        f.setWeekdays(Set.of(6, 7));
        f.setPriority(10);
        f.setRoomTypeIds(Set.of(typeId));
        return f;
    }

    private VoucherForm voucher() {
        var f = new VoucherForm();
        f.setCode("WELCOME");
        f.setName("Welcome voucher");
        f.setDiscountValue(new BigDecimal("10"));
        f.setMaxDiscountAmount(new BigDecimal("150000"));
        f.setStartsAt(LocalDateTime.now(support.zone()).minusHours(1).withNano(0));
        f.setExpiresAt(f.getStartsAt().plusMonths(1));
        f.setMaxUses(10);
        return f;
    }

    private DepositPolicyForm deposit() {
        var f = new DepositPolicyForm();
        f.setCode("TEST_DEPOSIT");
        f.setName("30 percent");
        f.setAmountType(DepositAmountType.PERCENT);
        f.setAmountValue(new BigDecimal("30"));
        return f;
    }

    private CancellationPolicyForm cancellation() {
        var f = new CancellationPolicyForm();
        f.setCode("TEST_CANCEL");
        f.setName("Flexible test");
        var zero = new CancellationTierForm();
        zero.setFeeType(FeeType.PERCENT);
        zero.setFeeValue(new BigDecimal("50"));
        var free = new CancellationTierForm();
        free.setMinHoursBeforeCheckin(48);
        f.setRules(new ArrayList<>(List.of(zero, free)));
        return f;
    }

    private SurchargePolicyForm surcharge() {
        var f = new SurchargePolicyForm();
        f.setCode("TEST_LATE");
        f.setName("Late check-out");
        f.setRoomTypeId(typeId);
        f.setFromMinutes(0);
        f.setToMinutes(180);
        f.setAmountType(SurchargeAmountType.PER_HOUR);
        f.setAmountValue(new BigDecimal("100000"));
        return f;
    }

    private PricingModels.Request quoteRequest(String voucher) {
        return new PricingModels.Request(
                List.of(
                        new PricingModels.Stay(
                                typeId, guard.today().plusDays(1), guard.today().plusDays(4), 1)),
                voucher,
                customerId,
                Instant.now(),
                null);
    }

    @Test
    void allPagesRenderInBothLanguagesWithPopulatedRulesPoliciesAndVoucher() throws Exception {
        Long r = rates.save(null, rate()),
                v = vouchers.save(null, voucher()),
                d = deposits.save(null, deposit()),
                c = cancellations.save(null, cancellation()),
                s = surcharges.save(null, surcharge());
        for (String lang : List.of("vi", "en"))
            for (String path :
                    List.of(
                            "/admin/pricing",
                            "/admin/pricing/rules/create",
                            "/admin/pricing/rules/" + r + "/edit",
                            "/admin/pricing/simulator",
                            "/admin/vouchers",
                            "/admin/vouchers/create",
                            "/admin/vouchers/" + v + "/edit",
                            "/admin/policies",
                            "/admin/policies/deposits/create",
                            "/admin/policies/deposits/" + d + "/edit",
                            "/admin/policies/cancellations/create",
                            "/admin/policies/cancellations/" + c + "/edit",
                            "/admin/policies/surcharges/create",
                            "/admin/policies/surcharges/" + s + "/edit"))
                mvc.perform(get(path).session(admin).param("lang", lang))
                        .andExpect(status().isOk())
                        .andExpect(content().string(not(containsString("??price."))));
    }

    @Test
    void basePricePostUsesPermissionValidationCsrfAndAudit() throws Exception {
        mvc.perform(
                        post("/admin/pricing/base-prices")
                                .session(admin)
                                .param("roomTypeId", typeId.toString())
                                .param("basePrice", "900000"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/pricing/base-prices")
                                .session(admin)
                                .with(csrf())
                                .param("roomTypeId", typeId.toString())
                                .param("basePrice", "900000"))
                .andExpect(status().is3xxRedirection());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT base_price FROM room_types WHERE id=?",
                                BigDecimal.class,
                                typeId))
                .isEqualByComparingTo("900000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_logs WHERE"
                                    + " action='ROOM_TYPE_BASE_PRICE_UPDATE' AND actor_user_id=?",
                                Long.class,
                                adminId))
                .isEqualTo(1);
        mvc.perform(
                        post("/admin/pricing/base-prices")
                                .session(admin)
                                .with(csrf())
                                .param("roomTypeId", typeId.toString())
                                .param("basePrice", "-1"))
                .andExpect(status().is3xxRedirection());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT base_price FROM room_types WHERE id=?",
                                BigDecimal.class,
                                typeId))
                .isEqualByComparingTo("900000");
    }

    @Test
    void usedRateCreatesNewVersionWithoutChangingNightSnapshot() {
        Long id = rates.save(null, rate());
        Long booking = booking(null, null);
        Long line = line(booking);
        night(line, id);
        var f = rates.form(id);
        f.setValue(new BigDecimal("1250000"));
        Long updated = rates.save(id, f);
        assertThat(updated).isNotEqualTo(id);
        assertThat(rates.get(updated).version()).isEqualTo(2);
        assertThat(rates.form(id).getValue()).isEqualByComparingTo("1000000");
        assertThat(rates.form(id).isActive()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT rate_rule_id FROM booking_room_nights WHERE"
                                        + " booking_room_id=?",
                                Long.class,
                                line))
                .isEqualTo(id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT nightly_rate FROM booking_room_nights WHERE"
                                        + " booking_room_id=?",
                                BigDecimal.class,
                                line))
                .isEqualByComparingTo("1000000");
        assertThatThrownBy(() -> rates.delete(id)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rates.toggle(id)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rates.save(id, f)).isInstanceOf(AppException.class);
    }

    @Test
    void unusedRateUpdatesInPlaceAndWeekdayMaskUsesIsoDays() {
        Long id = rates.save(null, rate());
        var f = rates.form(id);
        f.setValue(new BigDecimal("1100000"));
        assertThat(rates.save(id, f)).isEqualTo(id);
        assertThat(rates.get(id).version()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT weekday_mask FROM rate_rules WHERE id=?",
                                Integer.class,
                                id))
                .isEqualTo(96);
        rates.toggle(id);
        assertThat(rates.form(id).isActive()).isFalse();
        rates.delete(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rate_rules", Integer.class)).isZero();
    }

    @Test
    void cancellationVersionPreservesTiersAndMovesDefaultAtomically() {
        Long id = cancellations.save(null, cancellation());
        cancellations.makeDefault(id);
        Long b = booking(null, id);
        var f = cancellations.form(id);
        f.getRules().stream()
                .filter(t -> t.getMinHoursBeforeCheckin() == 0)
                .findFirst()
                .orElseThrow()
                .setFeeValue(new BigDecimal("75"));
        Long updated = cancellations.save(id, f);
        assertThat(updated).isNotEqualTo(id);
        assertThat(cancellations.get(updated).version()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT default_cancellation_policy_id FROM hotel_settings WHERE"
                                        + " id=1",
                                Long.class))
                .isEqualTo(updated);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT cancellation_policy_id FROM bookings WHERE id=?",
                                Long.class,
                                b))
                .isEqualTo(id);
        assertThat(cancellations.form(id).getRules())
                .anySatisfy(t -> assertThat(t.getFeeValue()).isEqualByComparingTo("50"));
        assertThatThrownBy(() -> cancellations.toggle(updated)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> cancellations.delete(id)).isInstanceOf(AppException.class);
    }

    @Test
    void depositVersionPreservesBookingTermsAndMovesDefault() {
        Long id = deposits.save(null, deposit());
        deposits.makeDefault(id);
        Long b = booking(id, null);
        var f = deposits.form(id);
        f.setAmountValue(new BigDecimal("50"));
        Long updated = deposits.save(id, f);
        assertThat(updated).isNotEqualTo(id);
        assertThat(deposits.get(updated).version()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT default_deposit_policy_id FROM hotel_settings WHERE id=1",
                                Long.class))
                .isEqualTo(updated);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deposit_policy_id FROM bookings WHERE id=?", Long.class, b))
                .isEqualTo(id);
        assertThat(deposits.form(id).getAmountValue()).isEqualByComparingTo("30");
        f = deposits.form(updated);
        f.setActive(false);
        var inactive = f;
        assertThatThrownBy(() -> deposits.save(updated, inactive)).isInstanceOf(AppException.class);
    }

    @Test
    void cancellationRequiresZeroAndUniqueThresholdsAndSafeFeeValues() {
        var f = cancellation();
        f.setRules(List.of(f.getRules().get(1)));
        assertThatThrownBy(() -> cancellations.save(null, f)).isInstanceOf(AppException.class);
        f.setRules(List.of(new CancellationTierForm(), new CancellationTierForm()));
        assertThatThrownBy(() -> cancellations.save(null, f)).isInstanceOf(AppException.class);
        f.setRules(List.of(new CancellationTierForm()));
        f.getRules().get(0).setFeeValue(BigDecimal.ONE);
        assertThatThrownBy(() -> cancellations.save(null, f)).isInstanceOf(AppException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cancellation_policies WHERE"
                                        + " code='TEST_CANCEL'",
                                Integer.class))
                .isZero();
    }

    @Test
    void cancellationTierReplacementDoesNotViolateUniqueConstraint() {
        Long id = cancellations.save(null, cancellation());
        var f = cancellations.form(id);
        f.getRules().get(0).setMinHoursBeforeCheckin(24);
        f.getRules().get(1).setMinHoursBeforeCheckin(0);
        cancellations.save(id, f);
        assertThat(cancellations.form(id).getRules())
                .extracting(CancellationTierForm::getMinHoursBeforeCheckin)
                .containsExactly(24, 0);
    }

    @Test
    void surchargeVersionKeepsChargeReference() {
        Long id = surcharges.save(null, surcharge());
        Long b = booking(null, null);
        jdbc.update(
                "INSERT INTO"
                    + " booking_charges(booking_id,surcharge_policy_id,source_key,kind,description,service_date,unit_price,amount,created_by)"
                    + " VALUES(?,?,'TEST_SURCHARGE','SURCHARGE','Late',?,100000,100000,?)",
                b,
                id,
                guard.today(),
                adminId);
        var f = surcharges.form(id);
        f.setAmountValue(new BigDecimal("150000"));
        Long updated = surcharges.save(id, f);
        assertThat(surcharges.get(updated).version()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT surcharge_policy_id FROM booking_charges WHERE"
                                        + " source_key='TEST_SURCHARGE'",
                                Long.class))
                .isEqualTo(id);
        assertThatThrownBy(() -> surcharges.delete(id)).isInstanceOf(AppException.class);
    }

    @Test
    void voucherQuotaUsesReservedAndRedeemedAndReleasedHistoryLocksTerms() throws Exception {
        Long id = vouchers.save(null, voucher());
        Long b = booking(null, null);
        redemption(id, b, "RESERVED");
        assertThat(vouchers.get(id).reserved()).isEqualTo(1);
        assertThat(vouchers.get(id).remaining()).isEqualTo(9);
        var f = vouchers.form(id);
        f.setDiscountValue(new BigDecimal("20"));
        var financial = f;
        assertThatThrownBy(() -> vouchers.save(id, financial)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> vouchers.delete(id)).isInstanceOf(AppException.class);
        f = vouchers.form(id);
        f.setExpiresAt(f.getExpiresAt().plusDays(2));
        f.setMaxUses(11);
        vouchers.save(id, f);
        assertThat(vouchers.get(id).remaining()).isEqualTo(10);
        var quote = quoteRequest("WELCOME");
        assertThatThrownBy(() -> engine.quote(quote)).isInstanceOf(AppException.class);
        jdbc.update("UPDATE voucher_redemptions SET status='RELEASED' WHERE voucher_id=?", id);
        assertThat(engine.quote(quote).voucherDiscountTotal()).isPositive();
        assertThat(vouchers.get(id).used()).isTrue();
        mvc.perform(get("/admin/vouchers/" + id + "/edit").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("d-none")));
    }

    @Test
    void voucherCannotShortenExpiryOrReduceLimitsAfterUse() {
        Long id = vouchers.save(null, voucher());
        redemption(id, booking(null, null), "REDEEMED");
        var f = vouchers.form(id);
        f.setMaxUses(9);
        var reduced = f;
        assertThatThrownBy(() -> vouchers.save(id, reduced)).isInstanceOf(AppException.class);
        f = vouchers.form(id);
        f.setExpiresAt(f.getExpiresAt().minusMinutes(1));
        var shorter = f;
        assertThatThrownBy(() -> vouchers.save(id, shorter)).isInstanceOf(AppException.class);
        f = vouchers.form(id);
        f.setStayFrom(guard.today());
        var altered = f;
        assertThatThrownBy(() -> vouchers.save(id, altered)).isInstanceOf(AppException.class);
    }

    @Test
    void voucherTimesRoundTripInHotelZoneAndCodeIsNormalized() {
        var f = voucher();
        f.setCode(" welcome_10 ");
        Long id = vouchers.save(null, f);
        assertThat(vouchers.form(id).getCode()).isEqualTo("WELCOME_10");
        assertThat(vouchers.form(id).getStartsAt()).isEqualTo(f.getStartsAt());
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT starts_at FROM vouchers WHERE id=?",
                                        java.sql.Timestamp.class,
                                        id)
                                .toInstant())
                .isEqualTo(f.getStartsAt().atZone(support.zone()).toInstant());
    }

    @Test
    void quoteIsReadOnlyAndIncludesDepositTaxAndPerNightPolicyData() {
        Long d = deposits.save(null, deposit());
        deposits.makeDefault(d);
        Long c = cancellations.save(null, cancellation());
        cancellations.makeDefault(c);
        Long v = vouchers.save(null, voucher());
        jdbc.update("UPDATE hotel_settings SET default_tax_percent=10 WHERE id=1");
        var q = engine.quote(quoteRequest("WELCOME"));
        assertThat(q.roomSubtotal()).isEqualByComparingTo("2250000");
        assertThat(q.taxAmount()).isEqualByComparingTo("225000");
        assertThat(q.totalAmount()).isEqualByComparingTo("2475000");
        assertThat(q.depositRequired()).isEqualByComparingTo("742500");
        assertThat(q.policies().deposit().id()).isEqualTo(d);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class)).isZero();
        assertThat(vouchers.get(v).reserved()).isZero();
    }

    @Test
    void simulatorPostRendersBreakdownForMultipleRoomsInBothLanguages() throws Exception {
        vouchers.save(null, voucher());
        for (String lang : List.of("vi", "en"))
            mvc.perform(
                            post("/admin/pricing/simulator")
                                    .session(admin)
                                    .with(csrf())
                                    .param("lang", lang)
                                    .param("rooms[0].roomTypeId", typeId.toString())
                                    .param("rooms[0].checkIn", guard.today().plusDays(1).toString())
                                    .param(
                                            "rooms[0].checkOut",
                                            guard.today().plusDays(3).toString())
                                    .param("rooms[0].quantity", "2")
                                    .param("voucherCode", "WELCOME")
                                    .param("customerId", customerId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeExists("quote"))
                    .andExpect(content().string(containsString("pricing-breakdown")))
                    .andExpect(content().string(not(containsString("??price."))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher_redemptions", Integer.class))
                .isZero();
    }

    @Test
    void readOnlyReceptionistCanSeeCalendarAndQuoteButCannotManage() throws Exception {
        users.createUser(
                CreateUserRequest.builder()
                        .fullName("Desk")
                        .email("pricing.desk@example.test")
                        .password("InitialPass123")
                        .roles(Set.of("RECEPTIONIST"))
                        .build());
        var desk = login("pricing.desk@example.test");
        for (String path : List.of("/admin/pricing", "/admin/pricing/simulator"))
            mvc.perform(get(path).session(desk)).andExpect(status().isOk());
        for (String path :
                List.of("/admin/pricing/rules/create", "/admin/vouchers", "/admin/policies"))
            mvc.perform(get(path).session(desk)).andExpect(status().isForbidden());
        mvc.perform(
                        post("/admin/pricing/base-prices")
                                .session(desk)
                                .with(csrf())
                                .param("roomTypeId", typeId.toString())
                                .param("basePrice", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/pricing").session(desk))
                .andExpect(content().string(not(containsString("name=\"basePrice\""))));
    }

    @Test
    void cmsRoutesRequireAdminOrReceptionistAndAllMutationsRequireCsrf() throws Exception {
        mvc.perform(
                        post("/admin/login")
                                .with(csrf())
                                .param("email", "pricing.customer@example.test")
                                .param("password", "InitialPass123"))
                .andExpect(redirectedUrl("/admin/login?accessDenied=true"));
        for (String path :
                List.of(
                        "/admin/pricing/rules",
                        "/admin/vouchers",
                        "/admin/policies/deposits",
                        "/admin/policies/cancellations",
                        "/admin/policies/surcharges",
                        "/admin/pricing/simulator"))
            mvc.perform(post(path).session(admin)).andExpect(status().isForbidden());
    }

    @Test
    void invalidFormsAndCalendarDateRangesAreLocalized() throws Exception {
        mvc.perform(
                        post("/admin/pricing/rules")
                                .session(admin)
                                .with(csrf())
                                .param("lang", "vi")
                                .param("code", "BAD CODE")
                                .param("name", "")
                                .param("startDate", "2026-11-01")
                                .param("endDate", "2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bắt buộc")));
        mvc.perform(
                        get("/admin/pricing")
                                .session(admin)
                                .param("from", "2026-11-01")
                                .param("until", "2027-01-01"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("calendarError"));
    }

    @Test
    void pricingPermissionWorksWithoutRoomManagementOrRoomRead() throws Exception {
        users.createUser(
                CreateUserRequest.builder()
                        .fullName("Rate manager")
                        .email("rates.only@example.test")
                        .password("InitialPass123")
                        .roles(Set.of("RECEPTIONIST"))
                        .build());
        Long
                role =
                        jdbc.queryForObject(
                                "SELECT id FROM roles WHERE code='RECEPTIONIST'", Long.class),
                read =
                        jdbc.queryForObject(
                                "SELECT id FROM permissions WHERE code='room.read'", Long.class),
                pricing =
                        jdbc.queryForObject(
                                "SELECT id FROM permissions WHERE code='pricing.manage'",
                                Long.class);
        boolean hadPricing =
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM role_permissions WHERE role_id=? AND"
                                        + " permission_id=?",
                                Integer.class,
                                role,
                                pricing)
                        > 0;
        try {
            jdbc.update(
                    "DELETE FROM role_permissions WHERE role_id=? AND permission_id=?", role, read);
            if (!hadPricing)
                jdbc.update(
                        "INSERT INTO role_permissions(role_id,permission_id) VALUES(?,?)",
                        role,
                        pricing);
            var manager = login("rates.only@example.test");
            mvc.perform(get("/admin/pricing").session(manager)).andExpect(status().isOk());
            mvc.perform(get("/admin/pricing/rules/create").session(manager))
                    .andExpect(status().isOk());
            mvc.perform(
                            post("/admin/pricing/base-prices")
                                    .session(manager)
                                    .with(csrf())
                                    .param("roomTypeId", typeId.toString())
                                    .param("basePrice", "850000"))
                    .andExpect(status().is3xxRedirection());
            mvc.perform(get("/admin/room-types").session(manager))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/admin/policies").session(manager)).andExpect(status().isForbidden());
        } finally {
            jdbc.update(
                    "INSERT INTO role_permissions(role_id,permission_id) VALUES(?,?)", role, read);
            if (!hadPricing)
                jdbc.update(
                        "DELETE FROM role_permissions WHERE role_id=? AND permission_id=?",
                        role,
                        pricing);
        }
    }

    @Test
    void missingBasePriceCannotSilentlyResetRateAndHugeCollectionsAreRejected() throws Exception {
        mvc.perform(
                        post("/admin/pricing/base-prices")
                                .session(admin)
                                .with(csrf())
                                .param("roomTypeId", typeId.toString()))
                .andExpect(status().is3xxRedirection());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT base_price FROM room_types WHERE id=?",
                                BigDecimal.class,
                                typeId))
                .isEqualByComparingTo("800000");
        mvc.perform(
                        post("/admin/pricing/simulator")
                                .session(admin)
                                .with(csrf())
                                .param("rooms[9999].roomTypeId", typeId.toString()))
                .andExpect(status().isBadRequest());
    }

    private Long booking(Long deposit, Long cancellation) {
        if (deposit == null)
            deposit =
                    jdbc.queryForObject(
                            "SELECT default_deposit_policy_id FROM hotel_settings WHERE id=1",
                            Long.class);
        if (cancellation == null)
            cancellation =
                    jdbc.queryForObject(
                            "SELECT default_cancellation_policy_id FROM hotel_settings WHERE id=1",
                            Long.class);
        String code = "PRICE_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO"
                    + " bookings(booking_code,customer_id,source,status,contact_name,contact_phone,deposit_policy_id,cancellation_policy_id,policy_snapshot)"
                    + " VALUES(?,?,'WALK_IN','CONFIRMED','Price Guest','0900000000',?,?,'{}')",
                code,
                customerId,
                deposit,
                cancellation);
        return jdbc.queryForObject(
                "SELECT id FROM bookings WHERE booking_code=?", Long.class, code);
    }

    private Long line(Long booking) {
        LocalDate d = guard.today().plusDays(1);
        jdbc.update(
                "INSERT INTO"
                    + " booking_rooms(booking_id,line_number,room_type_id,room_type_name_snapshot,check_in_date,check_out_date,expected_check_in_at,expected_check_out_at,adults)"
                    + " VALUES(?,1,?,'Standard',?,?,?, ?,2)",
                booking,
                typeId,
                d,
                d.plusDays(1),
                java.sql.Timestamp.valueOf(d.atTime(14, 0)),
                java.sql.Timestamp.valueOf(d.plusDays(1).atTime(12, 0)));
        return jdbc.queryForObject(
                "SELECT id FROM booking_rooms WHERE booking_id=?", Long.class, booking);
    }

    private void night(Long line, Long rule) {
        LocalDate d = guard.today().plusDays(1);
        jdbc.update(
                "INSERT INTO room_type_inventory(room_type_id,stay_date,total_rooms,sellable_rooms)"
                        + " VALUES(?,?,1,1)",
                typeId,
                d);
        jdbc.update(
                "INSERT INTO"
                    + " booking_room_nights(booking_room_id,stay_date,inventory_room_type_id,rate_rule_id,base_price_snapshot,nightly_rate,net_room_amount)"
                    + " VALUES(?,?,?,?,800000,1000000,1000000)",
                line,
                d,
                typeId,
                rule);
    }

    private void redemption(Long voucher, Long booking, String status) {
        jdbc.update(
                "INSERT INTO"
                    + " voucher_redemptions(voucher_id,booking_id,customer_id,discount_amount,voucher_snapshot,status)"
                    + " VALUES(?,?,?,100000,'{}',?)",
                voucher,
                booking,
                customerId,
                status);
    }
}
