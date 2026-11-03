package com.example.hotelmanagement.modules.pricing.dto;

import com.example.hotelmanagement.modules.policy.entity.*;
import com.example.hotelmanagement.modules.pricing.entity.*;
import com.example.hotelmanagement.modules.voucher.entity.VoucherDiscountType;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Immutable price/policy snapshots for quoting; no managed entities escape to callers. */
public final class PricingModels {
    private PricingModels() {}

    public record Stay(Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int quantity) {}

    public record Request(
            List<Stay> rooms,
            String voucherCode,
            Long customerId,
            Instant evaluationTime,
            Instant cancellationTime) {
        public Request {
            rooms = rooms == null ? List.of() : List.copyOf(rooms);
        }
    }

    public record Type(Long id, String name, BigDecimal basePrice, boolean active) {}

    public record Rule(
            Long id,
            String code,
            int version,
            String name,
            RateRuleKind kind,
            LocalDate startDate,
            LocalDate endDate,
            int weekdayMask,
            int minNights,
            Integer maxNights,
            RateValueType valueType,
            BigDecimal value,
            int priority,
            Set<Long> roomTypeIds) {
        public Rule {
            roomTypeIds = Set.copyOf(roomTypeIds);
        }
    }

    public record Voucher(
            Long id,
            String code,
            boolean active,
            VoucherDiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscountAmount,
            BigDecimal minRoomSubtotal,
            Instant startsAt,
            Instant expiresAt,
            LocalDate stayFrom,
            LocalDate stayUntil,
            Integer maxUses,
            int maxUsesPerCustomer,
            boolean combinableWithLengthDiscount,
            Set<Long> roomTypeIds,
            long totalConsumed,
            long customerConsumed) {
        public Voucher {
            roomTypeIds = Set.copyOf(roomTypeIds);
        }
    }

    public record Deposit(
            Long id,
            String code,
            int version,
            DepositAmountType amountType,
            BigDecimal amountValue,
            int paymentDueMinutes) {}

    public record Tier(int minHoursBeforeCheckin, FeeType feeType, BigDecimal feeValue) {}

    public record Cancellation(
            Long id,
            String code,
            int version,
            String name,
            String description,
            boolean customerCanCancel,
            int customerCancelDeadlineHours,
            FeeType noShowFeeType,
            BigDecimal noShowFeeValue,
            List<Tier> tiers) {
        public Cancellation {
            tiers = List.copyOf(tiers);
        }
    }

    public record Hotel(
            String timezone,
            String currency,
            LocalTime checkIn,
            LocalTime checkOut,
            BigDecimal taxPercent,
            Deposit deposit,
            Cancellation cancellation) {}

    public record Context(Map<Long, Type> types, List<Rule> rules, Voucher voucher, Hotel hotel) {
        public Context {
            types = Map.copyOf(types);
            rules = List.copyOf(rules);
        }
    }

    public record RuleMatch(Long id, String code, int version, String name, int priority) {}

    public record Night(
            int line,
            int room,
            Long roomTypeId,
            String roomTypeName,
            LocalDate date,
            BigDecimal basePriceSnapshot,
            RuleMatch overrideRule,
            BigDecimal nightlyRate,
            RuleMatch lengthRule,
            BigDecimal lengthDiscountAmount,
            BigDecimal voucherDiscountAmount,
            BigDecimal netRoomAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount) {}

    public record CancellationBand(
            int minHoursBeforeCheckin,
            Instant thresholdAt,
            FeeType feeType,
            BigDecimal feeValue,
            BigDecimal feeAmount) {}

    public record CancellationPreview(
            int line,
            int room,
            String roomTypeName,
            Instant expectedCheckIn,
            boolean customerCanCancel,
            Instant customerCancelDeadline,
            boolean noShow,
            Integer selectedThreshold,
            FeeType feeType,
            BigDecimal feeAmount,
            BigDecimal roomAmountAfterFee,
            BigDecimal noShowFee,
            List<CancellationBand> bands) {
        public CancellationPreview {
            bands = List.copyOf(bands);
        }
    }

    public record Quote(
            List<Night> nights,
            int roomCount,
            BigDecimal nightlySubtotal,
            BigDecimal lengthDiscountTotal,
            BigDecimal voucherDiscountTotal,
            BigDecimal roomSubtotal,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            BigDecimal depositRequired,
            Instant depositDueAt,
            Hotel policies,
            Instant evaluationTime,
            Instant cancellationTime,
            String voucherCode,
            boolean customerQuotaVerified,
            List<String> warningKeys,
            List<CancellationPreview> cancellations) {
        public Quote {
            nights = List.copyOf(nights);
            warningKeys = List.copyOf(warningKeys);
            cancellations = List.copyOf(cancellations);
        }
    }

    public record CalendarCell(LocalDate date, BigDecimal rate, RuleMatch rule) {}

    public record CalendarRow(Long roomTypeId, String name, List<CalendarCell> cells) {
        public CalendarRow {
            cells = List.copyOf(cells);
        }
    }

    public record Calendar(
            LocalDate from, LocalDate until, List<LocalDate> dates, List<CalendarRow> rows) {
        public Calendar {
            dates = List.copyOf(dates);
            rows = List.copyOf(rows);
        }
    }
}
