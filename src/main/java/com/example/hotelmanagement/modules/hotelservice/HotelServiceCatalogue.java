package com.example.hotelmanagement.modules.hotelservice;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.payment.BookingLedger;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HotelServiceCatalogue {
    private final OperationsStore db;
    private final BookingLedger ledger;

    public record Item(
            long categoryId,
            String code,
            String name,
            String description,
            String unit,
            BigDecimal unitPrice,
            BigDecimal taxPercent,
            boolean active) {}

    @PreAuthorize("hasAuthority('service.manage')")
    public long saveCategory(Long id, String code, String name, boolean active) {
        code = db.required(code, 30).toUpperCase(Locale.ROOT);
        name = db.required(name, 100);
        db.require(
                code.length() <= 30
                        && db.count(
                                        "SELECT COUNT(*) FROM service_categories WHERE"
                                                + " UPPER(code)=? AND id<>?",
                                        code,
                                        id == null ? -1 : id)
                                == 0,
                "duplicate");
        if (id == null)
            id = db.insert("service_categories", "code,name,is_active", code, name, active);
        else {
            db.one("SELECT id FROM service_categories WHERE id=? FOR UPDATE", id);
            db.update(
                    "UPDATE service_categories SET code=?,name=?,is_active=? WHERE id=?",
                    code,
                    name,
                    active,
                    id);
        }
        db.audit(
                "SERVICE_CATEGORY_SAVE",
                "SERVICE_CATEGORY",
                id,
                Map.of(),
                Map.of("code", code, "active", active),
                null);
        return id;
    }

    @PreAuthorize("hasAuthority('service.manage')")
    public long save(Long id, Item input) {
        String code = db.required(input.code(), 40).toUpperCase(Locale.ROOT);
        String name = db.required(input.name(), 150);
        String unit = db.required(input.unit(), 40);
        String description = db.optional(input.description(), 1000);
        BigDecimal price = input.unitPrice(), tax = input.taxPercent();
        db.require(
                price != null
                        && price.scale() <= 2
                        && price.signum() >= 0
                        && price.precision() - price.scale() <= 13
                        && tax != null
                        && tax.scale() <= 2
                        && tax.signum() >= 0
                        && tax.compareTo(BigDecimal.valueOf(100)) <= 0,
                "amount");
        db.one("SELECT id FROM service_categories WHERE id=? FOR UPDATE", input.categoryId());
        db.require(
                code.length() <= 40
                        && db.count(
                                        "SELECT COUNT(*) FROM services WHERE UPPER(code)=? AND"
                                                + " id<>?",
                                        code,
                                        id == null ? -1 : id)
                                == 0,
                "duplicate");
        if (id == null)
            id =
                    db.insert(
                            "services",
                            "category_id,code,name,description,unit,unit_price,tax_percent,is_active",
                            input.categoryId(),
                            code,
                            name,
                            description,
                            unit,
                            price,
                            tax,
                            input.active());
        else {
            db.one("SELECT id FROM services WHERE id=? FOR UPDATE", id);
            db.update(
                    """
                    UPDATE services SET category_id=?,code=?,name=?,description=?,unit=?,unit_price=?,tax_percent=?,is_active=?,
                    updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                    """,
                    input.categoryId(),
                    code,
                    name,
                    description,
                    unit,
                    price,
                    tax,
                    input.active(),
                    id);
        }
        db.audit(
                "SERVICE_SAVE",
                "SERVICE",
                id,
                Map.of(),
                Map.of("code", code, "price", price, "active", input.active()),
                null);
        return id;
    }

    @PreAuthorize("hasAuthority('service.manage')")
    public void delete(long id, boolean category) {
        if (category) {
            db.one("SELECT id FROM service_categories WHERE id=? FOR UPDATE", id);
            db.require(
                    db.count("SELECT COUNT(*) FROM services WHERE category_id=?", id) == 0, "used");
            db.update("DELETE FROM service_categories WHERE id=?", id);
        } else {
            db.one("SELECT id FROM services WHERE id=? FOR UPDATE", id);
            db.require(
                    db.count("SELECT COUNT(*) FROM service_usages WHERE service_id=?", id) == 0,
                    "used");
            db.update("DELETE FROM services WHERE id=?", id);
        }
        db.audit(
                "SERVICE_DELETE",
                category ? "SERVICE_CATEGORY" : "SERVICE",
                id,
                Map.of(),
                Map.of(),
                null);
    }

    @PreAuthorize("hasAuthority('service.usage.manage')")
    public long use(
            long booking, long line, long service, BigDecimal quantity, String key, String notes) {
        return useReviewed(booking, line, service, quantity, key, notes, null, null);
    }

    @PreAuthorize("hasAuthority('service.usage.manage')")
    public long useReviewed(
            long booking,
            long line,
            long service,
            BigDecimal quantity,
            String key,
            String notes,
            BigDecimal expectedPrice,
            BigDecimal expectedTax) {
        db.lockBooking(booking);
        db.require(key != null && key.matches("[A-F0-9]{24}") && db.actor() != null, "input");
        db.require(
                quantity != null
                        && quantity.scale() <= 3
                        && quantity.signum() > 0
                        && quantity.precision() - quantity.scale() <= 7,
                "amount");
        var prior =
                db.rows(
                        "SELECT * FROM service_usages WHERE booking_id=? AND request_key=?",
                        booking,
                        key);
        if (!prior.isEmpty()) {
            var usage = prior.get(0);
            db.require(
                    usage.id("booking_room_id") == line
                            && usage.id("service_id") == service
                            && usage.money("quantity").compareTo(quantity) == 0,
                    "idempotency");
            return usage.id("id");
        }
        ledger.editable(booking);
        db.require("CHECKED_IN".equals(db.bookingRoom(booking, line).s("status")), "state");
        var allocation =
                db.one(
                        "SELECT id FROM room_allocations WHERE booking_room_id=? AND"
                                + " status='OCCUPIED'",
                        line);
        var item =
                db.one(
                        """
                SELECT s.* FROM services s JOIN service_categories c ON c.id=s.category_id
                WHERE s.id=? AND s.is_active=TRUE AND c.is_active=TRUE FOR UPDATE
                """,
                        service);
        if (expectedPrice != null || expectedTax != null)
            db.require(
                    expectedPrice != null
                            && expectedTax != null
                            && expectedPrice.compareTo(item.money("unit_price")) == 0
                            && expectedTax.compareTo(item.money("tax_percent")) == 0,
                    "priceChanged");
        Instant now = Instant.now();
        long id =
                db.insert(
                        "service_usages",
                        "booking_id,booking_room_id,room_allocation_id,service_id,service_name_snapshot,unit_snapshot,"
                            + "quantity,unit_price_snapshot,tax_percent_snapshot,used_at,recorded_by,notes,request_key",
                        booking,
                        line,
                        allocation.id("id"),
                        service,
                        item.s("name"),
                        item.s("unit"),
                        quantity,
                        item.money("unit_price"),
                        item.money("tax_percent"),
                        ts(now),
                        db.actor(),
                        db.optional(notes, 500),
                        key);
        var day = now.atZone(db.zone()).toLocalDate();
        ledger.charge(
                booking,
                line,
                null,
                id,
                null,
                null,
                null,
                "SERVICE:" + id,
                "SERVICE",
                item.s("name"),
                day,
                quantity,
                item.money("unit_price"),
                item.money("tax_percent"));
        BigDecimal raw =
                quantity.multiply(item.money("unit_price"))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal rounded = vnd(raw);
        if (rounded.compareTo(raw) != 0)
            ledger.charge(
                    booking,
                    line,
                    null,
                    id,
                    null,
                    null,
                    null,
                    "SERVICE-ROUND:" + id,
                    "ADJUSTMENT",
                    "Làm tròn VND · " + item.s("name"),
                    day,
                    BigDecimal.ONE,
                    rounded.subtract(raw),
                    null);
        BigDecimal tax = percent(rounded, item.money("tax_percent"));
        if (tax.signum() > 0)
            ledger.charge(
                    booking,
                    line,
                    null,
                    id,
                    null,
                    null,
                    null,
                    "SERVICE-TAX:" + id,
                    "TAX",
                    "VAT · " + item.s("name"),
                    day,
                    BigDecimal.ONE,
                    tax,
                    item.money("tax_percent"));
        db.audit(
                "SERVICE_USE",
                "BOOKING",
                booking,
                Map.of(),
                Map.of("usage", id, "quantity", quantity),
                notes);
        db.event("SERVICE:" + id, "BOOKING_SERVICE_ADDED", booking);
        return id;
    }

    @PreAuthorize("hasAuthority('service.usage.manage')")
    public void voidUsage(long booking, long id, String reason) {
        db.lockBooking(booking);
        var usage = db.one("SELECT * FROM service_usages WHERE booking_id=? AND id=?", booking, id);
        if (usage.s("status").equals("VOIDED")) return;
        ledger.editable(booking);
        reason = db.required(reason, 500);
        db.update(
                "UPDATE service_usages SET"
                    + " status='VOIDED',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason=?"
                    + " WHERE id=?",
                db.actor(),
                reason,
                id);
        db.update(
                """
                UPDATE booking_charges SET status='VOIDED',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason=?
                WHERE booking_id=? AND service_usage_id=? AND status='POSTED'
                """,
                db.actor(),
                reason,
                booking,
                id);
        db.audit("SERVICE_USAGE_VOID", "BOOKING", booking, Map.of(), Map.of("usage", id), reason);
        db.event("SERVICE-VOID:" + id, "BOOKING_SERVICE_VOIDED", booking);
    }
}
