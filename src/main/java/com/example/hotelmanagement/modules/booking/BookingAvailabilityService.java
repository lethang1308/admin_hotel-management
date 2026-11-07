package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.allocation.RoomAllocationService;
import com.example.hotelmanagement.modules.allocation.RoomAllocationService.Window;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.pricing.dto.PricingModels.Stay;
import com.example.hotelmanagement.modules.pricing.service.RoomPricingEngineService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@PreAuthorize("hasAnyAuthority('room.read','booking.create')")
public class BookingAvailabilityService {
    private final OperationsStore db;
    private final RoomAllocationService allocation;
    private final HotelInventoryCoordinator inventory;
    private final RoomPricingEngineService pricing;

    public record Night(
            LocalDate date,
            int sellable,
            int held,
            int reserved,
            int remaining,
            boolean estimated) {}

    public record Option(
            long typeId,
            String name,
            int maxAdults,
            int maxChildren,
            int maxOccupancy,
            List<String> amenities,
            int continuous,
            int available,
            boolean canBook,
            BigDecimal averageNight,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal total,
            BigDecimal deposit,
            List<Night> nights) {}

    public record Result(
            LocalDate from,
            LocalDate until,
            int quantity,
            int adults,
            int children,
            boolean readyNow,
            Instant checkedAt,
            List<Option> options) {}

    public Result check(
            LocalDate from,
            LocalDate until,
            int adults,
            int children,
            int quantity,
            Long typeId,
            boolean readyNow) {
        validate(from, until, adults, children, quantity);
        db.require(!readyNow || from.equals(inventory.today()), "dates");
        var options = new ArrayList<Option>();
        for (var type :
                db.rows(
                        """
                SELECT * FROM room_types WHERE is_active=TRUE AND (? IS NULL OR id=?)
                AND max_adults>=? AND max_children>=? AND max_occupancy>=? ORDER BY base_price,id
                """,
                        typeId,
                        typeId,
                        adults,
                        children,
                        adults + children)) {
            long id = type.id("id");
            int continuous =
                    (int)
                            allocation
                                    .candidates(new Window(id, from, until), null, readyNow)
                                    .stream()
                                    .filter(RoomAllocationService.Candidate::available)
                                    .count();
            var cached = new HashMap<LocalDate, Row>();
            db.rows(
                            "SELECT * FROM room_type_inventory WHERE room_type_id=? AND"
                                + " stay_date>=? AND stay_date<?",
                            id,
                            from,
                            until)
                    .forEach(r -> cached.put(r.date("stay_date"), r));
            var physical =
                    db.rows(
                            "SELECT sell_from,sell_until FROM rooms WHERE room_type_id=? AND"
                                + " status<>'INACTIVE'",
                            id);
            var occupied = new HashMap<LocalDate, Row>();
            db.rows(
                            """
                    SELECT stay_date,
                    SUM(CASE WHEN inventory_state='HELD' THEN 1 ELSE 0 END) AS held,
                    SUM(CASE WHEN inventory_state='RESERVED' THEN 1 ELSE 0 END) AS reserved
                    FROM booking_room_nights WHERE inventory_room_type_id=? AND stay_date>=? AND stay_date<?
                    GROUP BY stay_date
                    """,
                            id,
                            from,
                            until)
                    .forEach(r -> occupied.put(r.date("stay_date"), r));
            var blocked = new HashMap<LocalDate, Integer>();
            db.rows(
                            """
                    SELECT c.stay_date,COUNT(*) AS blocked FROM room_night_claims c JOIN rooms r ON r.id=c.room_id
                    WHERE r.room_type_id=? AND c.stay_date>=? AND c.stay_date<? AND c.room_block_id IS NOT NULL GROUP BY c.stay_date
                    """,
                            id,
                            from,
                            until)
                    .forEach(r -> blocked.put(r.date("stay_date"), r.n("blocked")));
            var nights = new ArrayList<Night>();
            int minimum = Integer.MAX_VALUE;
            for (var date : from.datesUntil(until).toList()) {
                var old = cached.get(date);
                var consumed = occupied.get(date);
                int total =
                        (int)
                                physical.stream()
                                        .filter(
                                                r ->
                                                        !r.date("sell_from").isAfter(date)
                                                                && (r.s("sell_until") == null
                                                                        || r.date("sell_until")
                                                                                .isAfter(date)))
                                        .count();
                int held = consumed == null ? 0 : consumed.n("held"),
                        reserved = consumed == null ? 0 : consumed.n("reserved");
                int sellable = Math.max(0, total - blocked.getOrDefault(date, 0));
                int remaining = Math.max(0, sellable - held - reserved);
                if (old != null) {
                    remaining =
                            Math.min(
                                    remaining,
                                    Math.max(
                                            0,
                                            old.n("sellable_rooms")
                                                    - old.n("held_rooms")
                                                    - old.n("reserved_rooms")));
                    sellable = old.n("sellable_rooms");
                    held = old.n("held_rooms");
                    reserved = old.n("reserved_rooms");
                }
                minimum = Math.min(minimum, remaining);
                nights.add(new Night(date, sellable, held, reserved, remaining, old == null));
            }
            int available = Math.min(continuous, minimum);
            var quote = pricing.quoteAvailability(new Stay(id, from, until, quantity));
            var amenities =
                    db
                            .rows(
                                    """
                    SELECT a.name FROM amenities a JOIN room_type_amenities ra ON ra.amenity_id=a.id
                    WHERE ra.room_type_id=? ORDER BY a.name LIMIT 8
                    """,
                                    id)
                            .stream()
                            .map(r -> r.s("name"))
                            .toList();
            options.add(
                    new Option(
                            id,
                            type.s("name"),
                            type.n("max_adults"),
                            type.n("max_children"),
                            type.n("max_occupancy"),
                            amenities,
                            continuous,
                            available,
                            available >= quantity,
                            quote.roomSubtotal()
                                    .divide(
                                            BigDecimal.valueOf(
                                                    ChronoUnit.DAYS.between(from, until)
                                                            * quantity),
                                            0,
                                            RoundingMode.HALF_UP),
                            quote.roomSubtotal(),
                            quote.taxAmount(),
                            quote.totalAmount(),
                            quote.depositRequired(),
                            nights));
        }
        return new Result(
                from, until, quantity, adults, children, readyNow, Instant.now(), options);
    }

    public void validate(LocalDate from, LocalDate until, int adults, int children, int quantity) {
        db.require(
                from != null
                        && until != null
                        && !from.isBefore(inventory.today())
                        && until.isAfter(from)
                        && !until.isAfter(inventory.horizon())
                        && ChronoUnit.DAYS.between(from, until) <= 366
                        && quantity > 0
                        && quantity <= 12
                        && ChronoUnit.DAYS.between(from, until) * quantity <= 3660
                        && adults > 0
                        && adults <= 100
                        && children >= 0
                        && children <= 99,
                "dates");
    }
}
