package com.example.hotelmanagement.modules.payment;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.math.*;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class BookingLedger {
    private final OperationsStore db;

    public record Balance(BigDecimal total, BigDecimal collected, BigDecimal refunded) {
        public BigDecimal net() {
            return collected.subtract(refunded);
        }

        public BigDecimal debt() {
            return total.subtract(net());
        }

        public BigDecimal refundDue() {
            return debt().negate().max(ZERO);
        }
    }

    public Balance balance(long booking) {
        return new Balance(
                db.sum(
                        "SELECT COALESCE(SUM(amount),0) FROM booking_charges WHERE booking_id=? AND"
                            + " status='POSTED'",
                        booking),
                payments(booking, "kind<>'REFUND'"),
                payments(booking, "kind='REFUND'"));
    }

    private BigDecimal payments(long booking, String filter) {
        return db.sum(
                "SELECT COALESCE(SUM(amount),0) FROM payment_transactions WHERE booking_id=? AND"
                    + " status='SUCCEEDED' AND "
                        + filter,
                booking);
    }

    public void editable(long booking) {
        db.require(
                db.count(
                                "SELECT COUNT(*) FROM invoices WHERE booking_id=? AND"
                                    + " status='ISSUED'",
                                booking)
                        == 0,
                "invoiced");
    }

    public long charge(
            long booking,
            Long room,
            Long night,
            Long usage,
            Long cancellation,
            Long surcharge,
            Long adjusts,
            String key,
            String kind,
            String label,
            LocalDate day,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal taxRate) {
        BigDecimal amount = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
        db.require(amount.abs().compareTo(new BigDecimal("9999999999999.99")) <= 0, "amount");
        return db.insert(
                "booking_charges",
                "booking_id,booking_room_id,booking_room_night_id,service_usage_id,cancellation_id,surcharge_policy_id,"
                    + "adjusts_charge_id,source_key,kind,description,service_date,quantity,unit_price,amount,tax_percent_snapshot,created_by",
                booking,
                room,
                night,
                usage,
                cancellation,
                surcharge,
                adjusts,
                key,
                kind,
                label,
                day,
                quantity,
                price,
                amount,
                taxRate,
                db.actor());
    }

    public Long reverseNight(long booking, long room, long night, Long cancellation, String key) {
        Long roomCharge = null;
        for (var original :
                db.rows(
                        """
                SELECT * FROM booking_charges WHERE booking_id=? AND booking_room_night_id=?
                  AND status='POSTED' AND (kind IN ('ROOM','ROOM_ADJUSTMENT') OR (kind='TAX' AND adjusts_charge_id IS NULL))
                  AND source_key IN (?,?)
                """,
                        booking,
                        night,
                        "ROOM:" + night,
                        "ROOM-TAX:" + night)) {
            String kind = original.s("kind").equals("TAX") ? "TAX" : "ROOM_ADJUSTMENT";
            charge(
                    booking,
                    room,
                    night,
                    null,
                    cancellation,
                    null,
                    original.id("id"),
                    key + ":" + original.id("id"),
                    kind,
                    "Điều chỉnh · " + original.s("description"),
                    original.date("service_date"),
                    BigDecimal.ONE,
                    original.money("amount").negate(),
                    null);
            if (!kind.equals("TAX")) roomCharge = original.id("id");
        }
        return roomCharge;
    }
}
