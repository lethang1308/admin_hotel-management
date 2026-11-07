package com.example.hotelmanagement.modules.pricing.service;

import static com.example.hotelmanagement.modules.pricing.dto.PricingModels.*;

import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.hotel.repository.HotelSettingRepository;
import com.example.hotelmanagement.modules.policy.repository.*;
import com.example.hotelmanagement.modules.pricing.dto.PricingModels;
import com.example.hotelmanagement.modules.pricing.repository.RateRuleRepository;
import com.example.hotelmanagement.modules.roomtype.repository.RoomTypeRepository;
import com.example.hotelmanagement.modules.voucher.repository.VoucherRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RoomPricingEngineService {
    private final RoomTypeRepository types;
    private final RateRuleRepository rules;
    private final VoucherRepository vouchers;
    private final HotelSettingRepository hotels;
    private final DepositPolicyRepository deposits;
    private final CancellationPolicyRepository cancellations;
    private final PricingSupport support;
    private final PricingCalculator calculator;
    private final CmsMessages messages;

    @PreAuthorize("hasAnyAuthority('pricing.manage','booking.create')")
    public Quote quote(Request request) {
        if (request.customerId() != null)
            support.require(
                    support.count("SELECT COUNT(*) FROM customers WHERE id=?", request.customerId())
                            == 1,
                    "price.error.customer");
        try {
            return calculator.quote(request, context(request.voucherCode(), request.customerId()));
        } catch (PricingCalculationException e) {
            throw messages.error(e.getMessage());
        }
    }

    @PreAuthorize("hasAnyAuthority('room.read','booking.create')")
    public Quote quoteAvailability(Stay stay) {
        try {
            return calculator.quote(
                    new Request(List.of(stay), null, null, Instant.now(), null),
                    context(null, null));
        } catch (PricingCalculationException error) {
            throw messages.error(error.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('booking.update.any')")
    public Quote quoteAdjustment(Stay stay) {
        try {
            return calculator.quote(
                    new Request(List.of(stay), null, null, Instant.now(), null),
                    context(null, null));
        } catch (PricingCalculationException e) {
            throw messages.error(e.getMessage());
        }
    }

    @PreAuthorize("hasAnyAuthority('pricing.manage','room.read')")
    public PricingModels.Calendar calendar(LocalDate from, LocalDate until) {
        try {
            return calculator.calendar(context(null, null), from, until);
        } catch (PricingCalculationException e) {
            throw messages.error(e.getMessage());
        }
    }

    private Context context(String voucherCode, Long customerId) {
        var h = hotels.findById(1L).orElseThrow(() -> messages.error("price.error.configuration"));
        var d =
                deposits.findById(h.getDefaultDepositPolicyId())
                        .orElseThrow(() -> messages.error("price.error.configuration"));
        var c =
                cancellations
                        .findById(h.getDefaultCancellationPolicyId())
                        .orElseThrow(() -> messages.error("price.error.configuration"));
        support.require(d.isActive() && c.isActive(), "price.error.configuration");
        Map<Long, Type> typeData = new LinkedHashMap<>();
        types.findAll()
                .forEach(
                        t ->
                                typeData.put(
                                        t.getId(),
                                        new Type(
                                                t.getId(),
                                                t.getName(),
                                                t.getBasePrice(),
                                                t.isActive())));
        List<Rule> ruleData =
                rules.findAll().stream()
                        .filter(r -> r.isActive())
                        .map(
                                r ->
                                        new Rule(
                                                r.getId(),
                                                r.getCode(),
                                                r.getVersion(),
                                                r.getName(),
                                                r.getKind(),
                                                r.getStartDate(),
                                                r.getEndDate(),
                                                r.getWeekdayMask(),
                                                r.getMinNights(),
                                                r.getMaxNights(),
                                                r.getValueType(),
                                                r.getValue(),
                                                r.getPriority(),
                                                r.getRoomTypeIds()))
                        .toList();
        PricingModels.Voucher v = null;
        if (voucherCode != null && !voucherCode.isBlank()) {
            var e =
                    vouchers.findByCodeIgnoreCase(voucherCode.trim())
                            .orElseThrow(() -> messages.error("price.error.voucherValidity"));
            v =
                    new PricingModels.Voucher(
                            e.getId(),
                            e.getCode(),
                            e.isActive(),
                            e.getDiscountType(),
                            e.getDiscountValue(),
                            e.getMaxDiscountAmount(),
                            e.getMinRoomSubtotal(),
                            e.getStartsAt(),
                            e.getExpiresAt(),
                            e.getStayFrom(),
                            e.getStayUntil(),
                            e.getMaxUses(),
                            e.getMaxUsesPerCustomer(),
                            e.isCombinableWithLengthDiscount(),
                            e.getRoomTypeIds(),
                            support.count(
                                    "SELECT COUNT(*) FROM voucher_redemptions WHERE voucher_id=?"
                                            + " AND status IN ('RESERVED','REDEEMED')",
                                    e.getId()),
                            customerId == null
                                    ? 0
                                    : support.count(
                                            "SELECT COUNT(*) FROM voucher_redemptions WHERE"
                                                + " voucher_id=? AND customer_id=? AND status IN"
                                                + " ('RESERVED','REDEEMED')",
                                            e.getId(),
                                            customerId));
        }
        var hotel =
                new Hotel(
                        h.getTimezone(),
                        h.getCurrency(),
                        h.getStandardCheckIn(),
                        h.getStandardCheckOut(),
                        h.getDefaultTaxPercent(),
                        new Deposit(
                                d.getId(),
                                d.getCode(),
                                d.getVersion(),
                                d.getAmountType(),
                                d.getAmountValue(),
                                d.getPaymentDueMinutes()),
                        new Cancellation(
                                c.getId(),
                                c.getCode(),
                                c.getVersion(),
                                c.getName(),
                                c.getDescription(),
                                c.isCustomerCanCancel(),
                                c.getCustomerCancelDeadlineHours(),
                                c.getNoShowFeeType(),
                                c.getNoShowFeeValue(),
                                c.getRules().stream()
                                        .map(
                                                t ->
                                                        new Tier(
                                                                t.getMinHoursBeforeCheckin(),
                                                                t.getFeeType(),
                                                                t.getFeeValue()))
                                        .toList()));
        return new Context(typeData, ruleData, v, hotel);
    }
}
