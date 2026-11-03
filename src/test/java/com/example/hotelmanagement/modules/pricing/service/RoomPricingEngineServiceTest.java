package com.example.hotelmanagement.modules.pricing.service;

import static com.example.hotelmanagement.modules.pricing.dto.PricingModels.*;

import static org.assertj.core.api.Assertions.*;

import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.pricing.entity.*;
import com.example.hotelmanagement.modules.voucher.entity.VoucherDiscountType;

import org.junit.jupiter.api.Test;

import java.math.*;
import java.time.*;
import java.util.*;

class RoomPricingEngineServiceTest {
    private final PricingCalculator engine = new PricingCalculator();
    private static final LocalDate START = LocalDate.of(2026, 11, 6);
    private static final Instant AT = Instant.parse("2026-11-03T02:00:00Z");

    private BigDecimal bd(long n) {
        return BigDecimal.valueOf(n);
    }

    private Hotel hotel(DepositAmountType deposit, BigDecimal value, BigDecimal tax) {
        return new Hotel(
                "Asia/Ho_Chi_Minh",
                "VND",
                LocalTime.of(14, 0),
                LocalTime.NOON,
                tax,
                new Deposit(1L, "DP", 1, deposit, value, 30),
                new Cancellation(
                        1L,
                        "CP",
                        1,
                        "Flexible",
                        null,
                        true,
                        12,
                        FeeType.PERCENT,
                        bd(100),
                        List.of(
                                new Tier(0, FeeType.PERCENT, bd(50)),
                                new Tier(24, FeeType.FIRST_NIGHT, bd(0)),
                                new Tier(48, FeeType.NONE, bd(0)))));
    }

    private Context context(List<Rule> rules, Voucher voucher) {
        return new Context(
                Map.of(
                        1L,
                        new Type(1L, "Deluxe", bd(800), true),
                        2L,
                        new Type(2L, "Suite", bd(1200), true)),
                rules,
                voucher,
                hotel(DepositAmountType.PERCENT, bd(30), bd(10)));
    }

    private Rule rule(
            long id,
            RateRuleKind kind,
            RateValueType type,
            long value,
            int mask,
            int priority,
            int min,
            Integer max,
            LocalDate from,
            LocalDate until) {
        return new Rule(
                id,
                "R" + id,
                1,
                "Rule " + id,
                kind,
                from,
                until,
                mask,
                min,
                max,
                type,
                bd(value),
                priority,
                Set.of(1L));
    }

    private Rule rule(long id, RateRuleKind kind, long value, int mask, int priority) {
        return rule(
                id,
                kind,
                RateValueType.FIXED,
                value,
                mask,
                priority,
                1,
                null,
                START.minusMonths(1),
                START.plusMonths(6));
    }

    private Voucher voucher(
            VoucherDiscountType kind, long amount, Long cap, boolean combine, Set<Long> types) {
        return new Voucher(
                1L,
                "WELCOME",
                true,
                kind,
                bd(amount),
                cap == null ? null : bd(cap),
                bd(0),
                AT.minusSeconds(1),
                AT.plusSeconds(3600),
                null,
                null,
                10,
                1,
                combine,
                types,
                0,
                0);
    }

    private Request request(LocalDate from, LocalDate until, String code) {
        return new Request(List.of(new Stay(1L, from, until, 1)), code, null, AT, null);
    }

    @Test
    void overridesUsePriorityThenIdAndNeverStack() {
        var weekend = rule(1, RateRuleKind.NIGHT_OVERRIDE, 1000, 96, 10);
        var holiday =
                rule(
                        2,
                        RateRuleKind.NIGHT_OVERRIDE,
                        2000,
                        127,
                        100,
                        START.plusDays(1),
                        START.plusDays(2));
        var tie =
                rule(
                        3,
                        RateRuleKind.NIGHT_OVERRIDE,
                        2100,
                        127,
                        100,
                        START.plusDays(1),
                        START.plusDays(2));
        var q =
                engine.quote(
                        request(START, START.plusDays(4), null),
                        context(List.of(weekend, holiday, tie), null));
        assertThat(q.nights())
                .extracting(Night::nightlyRate)
                .containsExactly(bd(800), bd(2100), bd(1000), bd(800));
        assertThat(q.nights().get(1).overrideRule().id()).isEqualTo(3);
        assertThat(q.nightlySubtotal()).isEqualByComparingTo("4700");
    }

    private Rule rule(
            long id,
            RateRuleKind kind,
            long value,
            int mask,
            int priority,
            LocalDate from,
            LocalDate until) {
        return rule(id, kind, RateValueType.FIXED, value, mask, priority, 1, null, from, until);
    }

    @Test
    void longStayUsesWholeStayLengthButAppliesOnlyMatchingNights() {
        var discount =
                rule(
                        1,
                        RateRuleKind.LENGTH_DISCOUNT,
                        RateValueType.PERCENT,
                        10,
                        96,
                        1,
                        3,
                        5,
                        START,
                        START.plusDays(10));
        var q =
                engine.quote(
                        request(START, START.plusDays(4), null), context(List.of(discount), null));
        assertThat(q.nights())
                .extracting(Night::lengthDiscountAmount)
                .containsExactly(bd(0), bd(80), bd(80), bd(0));
        var shorter =
                engine.quote(
                        request(START, START.plusDays(2), null), context(List.of(discount), null));
        assertThat(shorter.lengthDiscountTotal()).isZero();
    }

    @Test
    void fixedLengthDiscountIsClampedAndHigherPriorityWins() {
        var d = rule(1, RateRuleKind.LENGTH_DISCOUNT, 900, 127, 1);
        var lower = rule(2, RateRuleKind.LENGTH_DISCOUNT, 500, 127, 0);
        var q =
                engine.quote(
                        request(START, START.plusDays(1), null), context(List.of(d, lower), null));
        assertThat(q.nights().get(0).lengthRule().id()).isEqualTo(1);
        assertThat(q.roomSubtotal()).isZero();
        assertThat(q.totalAmount()).isZero();
    }

    @Test
    void percentVoucherCapAndAllocationAreExact() {
        var v = voucher(VoucherDiscountType.PERCENT, 25, 301L, true, Set.of());
        var q = engine.quote(request(START, START.plusDays(3), " welcome "), context(List.of(), v));
        assertThat(q.voucherDiscountTotal()).isEqualByComparingTo("301");
        assertThat(q.nights())
                .extracting(Night::voucherDiscountAmount)
                .containsExactly(bd(100), bd(100), bd(101));
        assertThat(q.roomSubtotal()).isEqualByComparingTo("2099");
        assertThat(q.warningKeys()).contains("price.warning.customerQuota");
        assertThat(q.customerQuotaVerified()).isFalse();
    }

    @Test
    void manyHalfUpSharesCannotMakeLastNightNegative() {
        var v = voucher(VoucherDiscountType.FIXED, 50, null, true, Set.of());
        var base = context(List.of(), v);
        base =
                new Context(
                        Map.of(1L, new Type(1L, "Tiny", bd(1), true)),
                        base.rules(),
                        v,
                        base.hotel());
        var q = engine.quote(request(START, START.plusDays(100), "WELCOME"), base);
        assertThat(q.nights())
                .allSatisfy(
                        n -> {
                            assertThat(n.netRoomAmount()).isNotNegative();
                            assertThat(n.voucherDiscountAmount()).isBetween(bd(0), bd(1));
                        });
        assertThat(
                        q.nights().stream()
                                .map(Night::voucherDiscountAmount)
                                .reduce(bd(0), BigDecimal::add))
                .isEqualByComparingTo("50");
    }

    @Test
    void nonCombinableVoucherSkipsLongDiscountNightsAndRestrictsTypes() {
        var discount = rule(1, RateRuleKind.LENGTH_DISCOUNT, 10, 96, 1);
        var v = voucher(VoucherDiscountType.FIXED, 500, null, false, Set.of(1L));
        var req =
                new Request(
                        List.of(
                                new Stay(1L, START, START.plusDays(3), 1),
                                new Stay(2L, START, START.plusDays(3), 1)),
                        "WELCOME",
                        1L,
                        AT,
                        null);
        var q = engine.quote(req, context(List.of(discount), v));
        assertThat(q.nights())
                .filteredOn(n -> n.roomTypeId().equals(2L) || n.lengthDiscountAmount().signum() > 0)
                .allSatisfy(n -> assertThat(n.voucherDiscountAmount()).isZero());
        assertThat(q.nights().get(0).voucherDiscountAmount()).isEqualByComparingTo("500");
        assertThat(q.customerQuotaVerified()).isTrue();
    }

    @Test
    void voucherMinimumUsesOnlyEligibleSubtotal() {
        var v = voucher(VoucherDiscountType.FIXED, 50, null, true, Set.of(1L));
        v =
                new Voucher(
                        v.id(),
                        v.code(),
                        v.active(),
                        v.discountType(),
                        v.discountValue(),
                        v.maxDiscountAmount(),
                        bd(1000),
                        v.startsAt(),
                        v.expiresAt(),
                        null,
                        null,
                        10,
                        1,
                        true,
                        v.roomTypeIds(),
                        0,
                        0);
        var req =
                new Request(
                        List.of(
                                new Stay(1L, START, START.plusDays(1), 1),
                                new Stay(2L, START, START.plusDays(1), 1)),
                        "WELCOME",
                        1L,
                        AT,
                        null);
        final var data = context(List.of(), v);
        assertThatThrownBy(() -> engine.quote(req, data))
                .isInstanceOf(PricingCalculationException.class)
                .hasMessage("price.error.voucherEligible");
    }

    @Test
    void voucherExpiryIsExclusiveAndQuotaCountsAreEnforced() {
        var v = voucher(VoucherDiscountType.FIXED, 50, null, true, Set.of());
        var req =
                new Request(
                        List.of(new Stay(1L, START, START.plusDays(1), 1)),
                        "WELCOME",
                        1L,
                        v.expiresAt(),
                        null);
        assertThatThrownBy(() -> engine.quote(req, context(List.of(), v)))
                .hasMessage("price.error.voucherValidity");
        var exhausted =
                new Voucher(
                        v.id(),
                        v.code(),
                        true,
                        v.discountType(),
                        v.discountValue(),
                        null,
                        bd(0),
                        v.startsAt(),
                        v.expiresAt(),
                        null,
                        null,
                        1,
                        1,
                        true,
                        Set.of(),
                        1,
                        0);
        assertThatThrownBy(
                        () ->
                                engine.quote(
                                        request(START, START.plusDays(1), "WELCOME"),
                                        context(List.of(), exhausted)))
                .hasMessage("price.error.voucherQuota");
        var customer =
                new Voucher(
                        v.id(),
                        v.code(),
                        true,
                        v.discountType(),
                        v.discountValue(),
                        null,
                        bd(0),
                        v.startsAt(),
                        v.expiresAt(),
                        null,
                        null,
                        10,
                        1,
                        true,
                        Set.of(),
                        0,
                        1);
        assertThatThrownBy(
                        () ->
                                engine.quote(
                                        new Request(req.rooms(), "WELCOME", 1L, AT, null),
                                        context(List.of(), customer)))
                .hasMessage("price.error.customerQuota");
    }

    @Test
    void voucherStayUntilAcceptsCheckoutAtBoundary() {
        var v = voucher(VoucherDiscountType.FIXED, 50, null, true, Set.of());
        v =
                new Voucher(
                        v.id(),
                        v.code(),
                        true,
                        v.discountType(),
                        v.discountValue(),
                        null,
                        bd(0),
                        v.startsAt(),
                        v.expiresAt(),
                        START,
                        START.plusDays(2),
                        10,
                        1,
                        true,
                        Set.of(),
                        0,
                        0);
        final var data = context(List.of(), v);
        assertThat(
                        engine.quote(request(START, START.plusDays(2), "WELCOME"), data)
                                .voucherDiscountTotal())
                .isEqualByComparingTo("50");
        assertThatThrownBy(() -> engine.quote(request(START, START.plusDays(3), "WELCOME"), data))
                .hasMessage("price.error.voucherStay");
    }

    @Test
    void taxAndDepositUseDiscountedTotalsAndHalfUpRounding() {
        var base = context(List.of(), voucher(VoucherDiscountType.FIXED, 1, null, true, Set.of()));
        var q = engine.quote(request(START, START.plusDays(1), "WELCOME"), base);
        assertThat(q.roomSubtotal()).isEqualByComparingTo("799");
        assertThat(q.taxAmount()).isEqualByComparingTo("80");
        assertThat(q.totalAmount()).isEqualByComparingTo("879");
        assertThat(q.depositRequired()).isEqualByComparingTo("264");
        assertThat(q.depositDueAt()).isEqualTo(AT.plusSeconds(1800));
        var fixed =
                new Context(
                        base.types(),
                        base.rules(),
                        null,
                        hotel(DepositAmountType.FIXED, bd(999999), bd(0)));
        assertThat(engine.quote(request(START, START.plusDays(1), null), fixed).depositRequired())
                .isEqualByComparingTo("800");
    }

    @Test
    void cancellationSelectsExactHourThresholdAndFirstNightAfterDiscount() {
        var data =
                context(List.of(), voucher(VoucherDiscountType.FIXED, 100, null, true, Set.of()));
        Instant checkIn = START.atTime(14, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        var rooms = List.of(new Stay(1L, START, START.plusDays(2), 2));
        var q =
                engine.quote(
                        new Request(rooms, "WELCOME", null, AT, checkIn.minusSeconds(48 * 3600)),
                        data);
        assertThat(q.cancellations())
                .hasSize(2)
                .allSatisfy(
                        c -> {
                            assertThat(c.feeAmount()).isZero();
                            assertThat(c.selectedThreshold()).isEqualTo(48);
                        });
        q =
                engine.quote(
                        new Request(rooms, "WELCOME", null, AT, checkIn.minusSeconds(24 * 3600)),
                        data);
        assertThat(q.cancellations())
                .allSatisfy(
                        c -> {
                            assertThat(c.feeType()).isEqualTo(FeeType.FIRST_NIGHT);
                            assertThat(c.feeAmount()).isEqualByComparingTo("775");
                        });
        q =
                engine.quote(
                        new Request(
                                rooms, "WELCOME", null, AT, checkIn.minusSeconds(24 * 3600 - 1)),
                        data);
        assertThat(q.cancellations()).allSatisfy(c -> assertThat(c.selectedThreshold()).isZero());
        q = engine.quote(new Request(rooms, "WELCOME", null, AT, checkIn.plusSeconds(1)), data);
        assertThat(q.cancellations())
                .allSatisfy(
                        c -> {
                            assertThat(c.noShow()).isTrue();
                            assertThat(c.customerCanCancel()).isFalse();
                            assertThat(c.feeAmount()).isEqualByComparingTo("1550");
                        });
    }

    @Test
    void calculatorRejectsInvalidInputAndCalendarBoundaries() {
        var data = context(List.of(), null);
        assertThatThrownBy(() -> engine.quote(request(START, START, null), data))
                .hasMessage("price.error.request");
        assertThatThrownBy(() -> engine.quote(request(START, START.plusDays(367), null), data))
                .hasMessage("price.error.quoteLimit");
        assertThatThrownBy(() -> engine.calendar(data, START, START.plusDays(32)))
                .hasMessage("price.error.calendarRange");
        assertThat(engine.calendar(data, START, START.plusDays(14)).rows()).hasSize(2);
    }

    @Test
    void fixedCancellationIsCappedAndNoDepositHasNoDeadline() {
        var base = context(List.of(), null);
        var cp =
                new Cancellation(
                        1L,
                        "FIXED",
                        1,
                        "Fixed fee",
                        null,
                        false,
                        0,
                        FeeType.FIXED,
                        bd(2000),
                        List.of(new Tier(0, FeeType.FIXED, bd(1000))));
        var h =
                new Hotel(
                        base.hotel().timezone(),
                        "VND",
                        LocalTime.of(14, 0),
                        LocalTime.NOON,
                        bd(0),
                        new Deposit(1L, "NONE", 1, DepositAmountType.NONE, bd(0), 15),
                        cp);
        var req =
                new Request(
                        List.of(new Stay(1L, START, START.plusDays(1), 1)),
                        null,
                        null,
                        AT,
                        START.atTime(13, 0).atZone(ZoneId.of(h.timezone())).toInstant());
        var q = engine.quote(req, new Context(base.types(), base.rules(), null, h));
        assertThat(q.depositRequired()).isZero();
        assertThat(q.depositDueAt()).isNull();
        assertThat(q.cancellations().get(0).feeAmount()).isEqualByComparingTo("800");
        assertThat(q.cancellations().get(0).customerCanCancel()).isFalse();
    }

    @Test
    void overrideLengthBoundsAndDateEndAreExclusive() {
        var r =
                rule(
                        1,
                        RateRuleKind.NIGHT_OVERRIDE,
                        RateValueType.FIXED,
                        1000,
                        127,
                        5,
                        2,
                        3,
                        START,
                        START.plusDays(2));
        var data = context(List.of(r), null);
        var q = engine.quote(request(START, START.plusDays(3), null), data);
        assertThat(q.nights())
                .extracting(Night::nightlyRate)
                .containsExactly(bd(1000), bd(1000), bd(800));
        assertThat(engine.quote(request(START, START.plusDays(4), null), data).nights())
                .allSatisfy(n -> assertThat(n.overrideRule()).isNull());
    }

    @Test
    void nightAccountingAlwaysBalancesAndInputSnapshotsAreImmutable() {
        var v = voucher(VoucherDiscountType.PERCENT, 33, 451L, true, Set.of());
        var d =
                rule(
                        1,
                        RateRuleKind.LENGTH_DISCOUNT,
                        RateValueType.PERCENT,
                        7,
                        127,
                        1,
                        1,
                        null,
                        START,
                        START.plusDays(20));
        var q = engine.quote(request(START, START.plusDays(14), "WELCOME"), context(List.of(d), v));
        assertThat(q.nights())
                .allSatisfy(
                        n -> {
                            assertThat(n.netRoomAmount())
                                    .isEqualByComparingTo(
                                            n.nightlyRate()
                                                    .subtract(n.lengthDiscountAmount())
                                                    .subtract(n.voucherDiscountAmount()));
                            assertThat(n.totalAmount())
                                    .isEqualByComparingTo(n.netRoomAmount().add(n.taxAmount()));
                        });
        assertThatThrownBy(() -> q.nights().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(q.nights().stream().map(Night::netRoomAmount).reduce(bd(0), BigDecimal::add))
                .isEqualByComparingTo(q.roomSubtotal());
    }
}
