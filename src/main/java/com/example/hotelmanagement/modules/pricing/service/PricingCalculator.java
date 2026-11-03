package com.example.hotelmanagement.modules.pricing.service;

import static com.example.hotelmanagement.modules.pricing.dto.PricingModels.*;

import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.pricing.dto.PricingModels.Calendar;
import com.example.hotelmanagement.modules.pricing.entity.*;
import com.example.hotelmanagement.modules.voucher.entity.VoucherDiscountType;

import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure deterministic calculation. No database writes, quota reservations or external calls. */
@Component
public class PricingCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO, HUNDRED = new BigDecimal("100");

    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }

    private static void require(boolean condition, String key) {
        if (!condition) throw new PricingCalculationException(key);
    }

    public Rule choose(List<Rule> rules, Long type, LocalDate date, int nights, RateRuleKind kind) {
        return rules.stream()
                .filter(
                        r ->
                                r.kind() == kind
                                        && r.roomTypeIds().contains(type)
                                        && !date.isBefore(r.startDate())
                                        && date.isBefore(r.endDate())
                                        && (r.weekdayMask()
                                                        & (1
                                                                << (date.getDayOfWeek().getValue()
                                                                        - 1)))
                                                != 0
                                        && nights >= r.minNights()
                                        && (r.maxNights() == null || nights <= r.maxNights()))
                .max(Comparator.comparingInt(Rule::priority).thenComparing(Rule::id))
                .orElse(null);
    }

    private RuleMatch match(Rule r) {
        return r == null
                ? null
                : new RuleMatch(r.id(), r.code(), r.version(), r.name(), r.priority());
    }

    public Quote quote(Request request, Context context) {
        require(
                request != null
                        && request.evaluationTime() != null
                        && !request.rooms().isEmpty()
                        && request.rooms().size() <= 20,
                "price.error.request");
        Hotel hotel = context.hotel();
        require(
                hotel != null
                        && hotel.deposit() != null
                        && hotel.cancellation() != null
                        && hotel.cancellation().tiers().stream()
                                .anyMatch(t -> t.minHoursBeforeCheckin() == 0),
                "price.error.configuration");
        List<Work> work = new ArrayList<>();
        int line = 0, roomCount = 0;
        long totalNights = 0;
        for (Stay stay : request.rooms()) {
            require(
                    stay.roomTypeId() != null
                            && stay.checkIn() != null
                            && stay.checkOut() != null
                            && stay.checkOut().isAfter(stay.checkIn())
                            && stay.quantity() > 0
                            && stay.quantity() <= 20,
                    "price.error.request");
            long n = ChronoUnit.DAYS.between(stay.checkIn(), stay.checkOut());
            roomCount += stay.quantity();
            totalNights += n * stay.quantity();
            require(n <= 366 && roomCount <= 50 && totalNights <= 3660, "price.error.quoteLimit");
            Type type = context.types().get(stay.roomTypeId());
            require(type != null && type.active(), "price.error.types");
            line++;
            for (int room = 1; room <= stay.quantity(); room++)
                for (LocalDate d = stay.checkIn(); d.isBefore(stay.checkOut()); d = d.plusDays(1)) {
                    Rule
                            override =
                                    choose(
                                            context.rules(),
                                            type.id(),
                                            d,
                                            (int) n,
                                            RateRuleKind.NIGHT_OVERRIDE),
                            length =
                                    choose(
                                            context.rules(),
                                            type.id(),
                                            d,
                                            (int) n,
                                            RateRuleKind.LENGTH_DISCOUNT);
                    BigDecimal rate = money(override == null ? type.basePrice() : override.value()),
                            discount =
                                    length == null
                                            ? ZERO
                                            : money(
                                                            length.valueType()
                                                                            == RateValueType.PERCENT
                                                                    ? rate.multiply(length.value())
                                                                            .divide(HUNDRED)
                                                                    : length.value())
                                                    .min(rate);
                    work.add(
                            new Work(
                                    line,
                                    room,
                                    type,
                                    d,
                                    match(override),
                                    rate,
                                    match(length),
                                    discount));
                }
        }
        String voucherCode =
                request.voucherCode() == null || request.voucherCode().isBlank()
                        ? null
                        : request.voucherCode().trim().toUpperCase(Locale.ROOT);
        BigDecimal voucherTotal = ZERO;
        List<String> warnings = new ArrayList<>();
        boolean identityVerified = voucherCode == null || request.customerId() != null;
        if (voucherCode != null) {
            Voucher v = context.voucher();
            require(
                    v != null
                            && v.code().equalsIgnoreCase(voucherCode)
                            && v.active()
                            && !request.evaluationTime().isBefore(v.startsAt())
                            && request.evaluationTime().isBefore(v.expiresAt()),
                    "price.error.voucherValidity");
            require(
                    v.maxUses() == null || v.totalConsumed() < v.maxUses(),
                    "price.error.voucherQuota");
            if (request.customerId() != null)
                require(v.customerConsumed() < v.maxUsesPerCustomer(), "price.error.customerQuota");
            else warnings.add("price.warning.customerQuota");
            for (Stay s : request.rooms())
                require(
                        (v.stayFrom() == null || !s.checkIn().isBefore(v.stayFrom()))
                                && (v.stayUntil() == null || !s.checkOut().isAfter(v.stayUntil())),
                        "price.error.voucherStay");
            List<Work> eligible =
                    work.stream()
                            .filter(
                                    w ->
                                            (v.roomTypeIds().isEmpty()
                                                            || v.roomTypeIds()
                                                                    .contains(w.type.id()))
                                                    && (v.combinableWithLengthDiscount()
                                                            || w.lengthDiscount.signum() == 0)
                                                    && w.afterLength().signum() > 0)
                            .toList();
            BigDecimal base =
                    eligible.stream().map(Work::afterLength).reduce(ZERO, BigDecimal::add);
            require(
                    base.signum() > 0 && base.compareTo(v.minRoomSubtotal()) >= 0,
                    "price.error.voucherEligible");
            voucherTotal =
                    money(
                            v.discountType() == VoucherDiscountType.PERCENT
                                    ? base.multiply(v.discountValue()).divide(HUNDRED)
                                    : v.discountValue());
            if (v.maxDiscountAmount() != null)
                voucherTotal = voucherTotal.min(money(v.maxDiscountAmount()));
            voucherTotal = voucherTotal.min(base);
            // HALF_UP shares with a bounded remainder. Naive rounding can over-allocate before the
            // last night.
            BigDecimal remaining = voucherTotal, suffix = base;
            for (int i = 0; i < eligible.size(); i++) {
                Work w = eligible.get(i);
                suffix = suffix.subtract(w.afterLength());
                BigDecimal desired =
                        i == eligible.size() - 1
                                ? remaining
                                : voucherTotal
                                        .multiply(w.afterLength())
                                        .divide(base, 0, RoundingMode.HALF_UP);
                BigDecimal lower = remaining.subtract(suffix).max(ZERO),
                        upper = w.afterLength().min(remaining);
                w.voucher = desired.max(lower).min(upper);
                remaining = remaining.subtract(w.voucher);
            }
            require(remaining.signum() == 0, "price.error.allocation");
        }
        List<Night> nights =
                work.stream()
                        .map(
                                w -> {
                                    BigDecimal net = w.afterLength().subtract(w.voucher),
                                            tax =
                                                    money(
                                                            net.multiply(hotel.taxPercent())
                                                                    .divide(HUNDRED));
                                    return new Night(
                                            w.line,
                                            w.room,
                                            w.type.id(),
                                            w.type.name(),
                                            w.date,
                                            w.type.basePrice(),
                                            w.override,
                                            w.rate,
                                            w.length,
                                            w.lengthDiscount,
                                            w.voucher,
                                            net,
                                            tax,
                                            net.add(tax));
                                })
                        .toList();
        BigDecimal gross = nights.stream().map(Night::totalAmount).reduce(ZERO, BigDecimal::add),
                tax = nights.stream().map(Night::taxAmount).reduce(ZERO, BigDecimal::add),
                net = nights.stream().map(Night::netRoomAmount).reduce(ZERO, BigDecimal::add),
                lengthTotal =
                        nights.stream()
                                .map(Night::lengthDiscountAmount)
                                .reduce(ZERO, BigDecimal::add),
                subtotal = nights.stream().map(Night::nightlyRate).reduce(ZERO, BigDecimal::add);
        Deposit dp = hotel.deposit();
        BigDecimal deposit =
                money(
                                switch (dp.amountType()) {
                                    case NONE -> ZERO;
                                    case PERCENT -> gross.multiply(dp.amountValue())
                                            .divide(HUNDRED);
                                    case FIXED -> dp.amountValue();
                                })
                        .min(gross);
        Instant cancelAt =
                request.cancellationTime() == null
                        ? request.evaluationTime()
                        : request.cancellationTime();
        var cancellationPreviews = cancellations(nights, request.rooms(), hotel, cancelAt);
        return new Quote(
                nights,
                roomCount,
                subtotal,
                lengthTotal,
                voucherTotal,
                net,
                tax,
                gross,
                deposit,
                deposit.signum() == 0
                        ? null
                        : request.evaluationTime().plus(dp.paymentDueMinutes(), ChronoUnit.MINUTES),
                hotel,
                request.evaluationTime(),
                cancelAt,
                voucherCode,
                identityVerified,
                warnings,
                cancellationPreviews);
    }

    private List<CancellationPreview> cancellations(
            List<Night> nights, List<Stay> stays, Hotel hotel, Instant at) {
        List<CancellationPreview> result = new ArrayList<>();
        var policy = hotel.cancellation();
        ZoneId zone = ZoneId.of(hotel.timezone());
        for (int i = 0; i < stays.size(); i++) {
            Stay stay = stays.get(i);
            final int line = i + 1;
            Instant checkIn = stay.checkIn().atTime(hotel.checkIn()).atZone(zone).toInstant();
            Instant deadline =
                    checkIn.minus(policy.customerCancelDeadlineHours(), ChronoUnit.HOURS);
            for (int room = 1; room <= stay.quantity(); room++) {
                final int roomIndex = room;
                List<Night> group =
                        nights.stream()
                                .filter(n -> n.line() == line && n.room() == roomIndex)
                                .toList();
                BigDecimal
                        base =
                                group.stream()
                                        .map(Night::netRoomAmount)
                                        .reduce(ZERO, BigDecimal::add),
                        first = group.get(0).netRoomAmount();
                boolean noShow = at.isAfter(checkIn);
                Tier selected =
                        policy.tiers().stream()
                                .filter(
                                        t ->
                                                !at.isAfter(
                                                        checkIn.minus(
                                                                t.minHoursBeforeCheckin(),
                                                                ChronoUnit.HOURS)))
                                .max(Comparator.comparingInt(Tier::minHoursBeforeCheckin))
                                .orElseGet(
                                        () ->
                                                policy.tiers().stream()
                                                        .filter(t -> t.minHoursBeforeCheckin() == 0)
                                                        .findFirst()
                                                        .orElseThrow());
                FeeType kind = noShow ? policy.noShowFeeType() : selected.feeType();
                BigDecimal value = noShow ? policy.noShowFeeValue() : selected.feeValue(),
                        fee = fee(kind, value, base, first);
                List<CancellationBand> bands =
                        policy.tiers().stream()
                                .sorted(
                                        Comparator.comparingInt(Tier::minHoursBeforeCheckin)
                                                .reversed())
                                .map(
                                        t ->
                                                new CancellationBand(
                                                        t.minHoursBeforeCheckin(),
                                                        checkIn.minus(
                                                                t.minHoursBeforeCheckin(),
                                                                ChronoUnit.HOURS),
                                                        t.feeType(),
                                                        t.feeValue(),
                                                        fee(
                                                                t.feeType(),
                                                                t.feeValue(),
                                                                base,
                                                                first)))
                                .toList();
                result.add(
                        new CancellationPreview(
                                line,
                                room,
                                group.get(0).roomTypeName(),
                                checkIn,
                                policy.customerCanCancel() && !noShow && !at.isAfter(deadline),
                                deadline,
                                noShow,
                                noShow ? null : selected.minHoursBeforeCheckin(),
                                kind,
                                fee,
                                base.subtract(fee),
                                fee(policy.noShowFeeType(), policy.noShowFeeValue(), base, first),
                                bands));
            }
        }
        return result;
    }

    private BigDecimal fee(FeeType kind, BigDecimal value, BigDecimal base, BigDecimal first) {
        return money(
                        switch (kind) {
                            case NONE -> ZERO;
                            case PERCENT -> base.multiply(value).divide(HUNDRED);
                            case FIXED -> value;
                            case FIRST_NIGHT -> first;
                        })
                .max(ZERO)
                .min(base);
    }

    public Calendar calendar(Context context, LocalDate from, LocalDate until) {
        require(
                from != null
                        && until != null
                        && until.isAfter(from)
                        && ChronoUnit.DAYS.between(from, until) <= 31,
                "price.error.calendarRange");
        List<LocalDate> dates = from.datesUntil(until).toList();
        List<CalendarRow> rows =
                context.types().values().stream()
                        .filter(Type::active)
                        .sorted(Comparator.comparing(Type::name).thenComparing(Type::id))
                        .map(
                                t ->
                                        new CalendarRow(
                                                t.id(),
                                                t.name(),
                                                dates.stream()
                                                        .map(
                                                                d -> {
                                                                    Rule rule =
                                                                            choose(
                                                                                    context.rules(),
                                                                                    t.id(),
                                                                                    d,
                                                                                    1,
                                                                                    RateRuleKind
                                                                                            .NIGHT_OVERRIDE);
                                                                    return new CalendarCell(
                                                                            d,
                                                                            money(
                                                                                    rule == null
                                                                                            ? t
                                                                                                    .basePrice()
                                                                                            : rule
                                                                                                    .value()),
                                                                            match(rule));
                                                                })
                                                        .toList()))
                        .toList();
        return new Calendar(from, until, dates, rows);
    }

    private static final class Work {
        final int line, room;
        final Type type;
        final LocalDate date;
        final RuleMatch override, length;
        final BigDecimal rate, lengthDiscount;
        BigDecimal voucher = ZERO;

        Work(
                int line,
                int room,
                Type type,
                LocalDate date,
                RuleMatch override,
                BigDecimal rate,
                RuleMatch length,
                BigDecimal lengthDiscount) {
            this.line = line;
            this.room = room;
            this.type = type;
            this.date = date;
            this.override = override;
            this.rate = rate;
            this.length = length;
            this.lengthDiscount = lengthDiscount;
        }

        BigDecimal afterLength() {
            return rate.subtract(lengthDiscount);
        }
    }
}
