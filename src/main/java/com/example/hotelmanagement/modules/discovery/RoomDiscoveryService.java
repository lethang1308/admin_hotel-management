package com.example.hotelmanagement.modules.discovery;

import static com.example.hotelmanagement.modules.discovery.DiscoveryModels.*;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.booking.OperationsStore.Row;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.pricing.dto.PricingModels;
import com.example.hotelmanagement.modules.pricing.dto.PricingModels.Quote;
import com.example.hotelmanagement.modules.pricing.service.RoomPricingEngineService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RoomDiscoveryService {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;
    private final RoomPricingEngineService pricing;

    private static final String PUBLISHED_REVIEWS =
            """
            FROM reviews v JOIN booking_rooms br ON br.id=v.booking_room_id
            WHERE v.status='PUBLISHED' AND br.room_type_id=?
            """;
    private static final String ACTIVE_TYPES =
            """
            SELECT t.*, COALESCE(r.rating,0) AS rating, COALESCE(r.review_count,0) AS review_count,
                   COALESCE(p.booking_count,0) AS booking_count
            FROM room_types t
            LEFT JOIN (
                SELECT br.room_type_id, AVG(v.rating) AS rating, COUNT(*) AS review_count
                FROM reviews v JOIN booking_rooms br ON br.id=v.booking_room_id
                WHERE v.status='PUBLISHED' GROUP BY br.room_type_id
            ) r ON r.room_type_id=t.id
            LEFT JOIN (
                SELECT room_type_id, COUNT(DISTINCT booking_id) AS booking_count
                FROM booking_rooms WHERE status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT')
                GROUP BY room_type_id
            ) p ON p.room_type_id=t.id
            WHERE t.is_active=TRUE
            """;
    private static final String AVAILABLE_ROOMS =
            """
            FROM rooms r JOIN floors f ON f.id=r.floor_id
            WHERE r.status<>'INACTIVE' AND f.is_active=TRUE
              AND r.sell_from<=%s AND (r.sell_until IS NULL OR r.sell_until>%s)
              AND NOT EXISTS (
                SELECT 1 FROM room_night_claims c
                WHERE c.room_id=r.id AND c.stay_date>=%s AND c.stay_date<=%s
              )
            """;

    public Hotel info() {
        Row h = hotel();
        var images =
                db.rows(
                        """
                SELECT i.image_url FROM room_type_images i JOIN room_types t ON t.id=i.room_type_id
                WHERE t.is_active=TRUE AND t.is_featured=TRUE
                ORDER BY t.id,i.sort_order,i.id LIMIT 1
                """);
        var now = OperationsStore.ts(Instant.now());
        var today = inventory.today();
        var offers =
                db
                        .rows(
                                """
                SELECT v.* FROM vouchers v
                WHERE v.is_active=TRUE AND v.starts_at<=? AND v.expires_at>?
                  AND (v.stay_until IS NULL OR v.stay_until>?)
                  AND (v.max_uses IS NULL OR v.max_uses > (
                    SELECT COUNT(*) FROM voucher_redemptions r
                    WHERE r.voucher_id=v.id AND r.status IN ('RESERVED','REDEEMED')
                  ))
                ORDER BY v.expires_at,v.id LIMIT 12
                """,
                                now,
                                now,
                                today)
                        .stream()
                        .map(
                                v ->
                                        new Offer(
                                                v.s("code"),
                                                v.s("name"),
                                                v.s("description"),
                                                v.s("discount_type"),
                                                decimal(v, "discount_value"),
                                                decimal(v, "min_room_subtotal"),
                                                v.money("max_discount_amount"),
                                                v.time("expires_at"),
                                                optionalDate(v, "stay_from"),
                                                optionalDate(v, "stay_until")))
                        .toList();
        return new Hotel(
                h.id("id"),
                h.s("name"),
                h.s("description"),
                h.s("address"),
                h.s("hotline"),
                h.s("email"),
                h.s("logo_url"),
                images.isEmpty() ? null : images.get(0).s("image_url"),
                h.s("currency"),
                h.s("standard_check_in"),
                h.s("standard_check_out"),
                today,
                inventory.horizon(),
                offers);
    }

    public Filters filters() {
        Row h = hotel();
        return new Filters(
                choices(
                        "SELECT code,name FROM room_types WHERE is_active=TRUE ORDER BY"
                            + " base_price,id"),
                choices("SELECT code,name FROM amenities WHERE is_active=TRUE ORDER BY name,id"),
                h.s("currency"),
                h.s("name"),
                h.s("address"));
    }

    private List<Choice> choices(String sql) {
        return db.rows(sql).stream().map(row -> new Choice(row.s("code"), row.s("name"))).toList();
    }

    public PageResponse<RoomCard> featured(int page, int size) {
        return cards(
                db.rows(
                        ACTIVE_TYPES
                                + " AND t.is_featured=TRUE ORDER BY rating DESC,review_count"
                                + " DESC,t.id"),
                page,
                size,
                null,
                Map.of(),
                Map.of());
    }

    PageResponse<RoomCard> favorites(long customer, int page, int size) {
        return cards(
                db.rows(
                        ACTIVE_TYPES
                                + """
                 AND EXISTS (SELECT 1 FROM favorites f WHERE f.room_type_id=t.id AND f.customer_id=?)
                 ORDER BY (SELECT f.created_at FROM favorites f WHERE f.room_type_id=t.id AND f.customer_id=?) DESC,t.id
                """,
                        customer,
                        customer),
                page,
                size,
                null,
                Map.of(),
                Map.of());
    }

    public PageResponse<RoomCard> search(Search filter, int page, int size) {
        Stay stay = filter.stay();
        validate(stay);
        checkPrice(filter.minPrice(), filter.maxPrice(), filter.minRating());
        var types = codes(filter.type(), true);
        var amenities = codes(filter.amenities(), false);
        var h = hotel();
        if (filter.city() != null && filter.city().length() > 150)
            throw bad("Địa điểm tìm kiếm quá dài.");
        if (filter.city() != null
                && !fold(h.s("address") + " " + h.s("name")).contains(fold(filter.city().strip())))
            return page(List.<RoomCard>of(), page, size, Function.identity());

        var amenityCodes = amenities.isEmpty() ? Map.<Long, Set<String>>of() : amenityCodes();
        var remaining = stock(stay);
        var candidates =
                db.rows(ACTIVE_TYPES).stream()
                        .filter(row -> fits(row, stay))
                        .filter(row -> types.isEmpty() || types.contains(typeCode(row.s("code"))))
                        .filter(
                                row ->
                                        amenities.isEmpty()
                                                || amenityCodes
                                                        .getOrDefault(row.id("id"), Set.of())
                                                        .containsAll(amenities))
                        .filter(
                                row ->
                                        filter.minRating() == null
                                                || decimal(row, "rating")
                                                                .compareTo(filter.minRating())
                                                        >= 0)
                        .filter(row -> remaining.getOrDefault(row.id("id"), 0) >= stay.roomsCount())
                        .toList();
        var quotes = quotes(candidates, stay);
        var matching =
                candidates.stream()
                        .filter(
                                row -> {
                                    Quote quote = quotes.get(row.id("id"));
                                    BigDecimal price = average(quote, stay);
                                    return quote.roomSubtotal().signum() > 0
                                            && (filter.minPrice() == null
                                                    || price.compareTo(filter.minPrice()) >= 0)
                                            && (filter.maxPrice() == null
                                                    || price.compareTo(filter.maxPrice()) <= 0);
                                })
                        .sorted(
                                sort(
                                        filter.sortBy(),
                                        row -> average(quotes.get(row.id("id")), stay)))
                        .toList();
        return cards(matching, page, size, stay, quotes, remaining);
    }

    public Detail detail(long id, Stay stay) {
        if (stay.checkIn() == null && stay.checkOut() == null) {
            LocalDate tomorrow = inventory.today().plusDays(1);
            stay =
                    new Stay(
                            tomorrow,
                            tomorrow.plusDays(2),
                            stay.adults(),
                            stay.children(),
                            stay.roomsCount());
        }
        validate(stay);
        Row room = room(id);
        var h = info();
        var quotes = quotes(List.of(room), stay);
        return new Detail(h, List.of(card(room, h, stay, quotes, stock(stay))), policy());
    }

    public Reviews reviews(long id, int page, int size) {
        room(id);
        PageRequest req = pageRequest(page, size);
        var stats =
                db.one(
                        "SELECT COUNT(*) AS total, COALESCE(AVG(v.rating),0) AS rating "
                                + PUBLISHED_REVIEWS,
                        id);
        var rows =
                db.rows(
                        """
                SELECT v.id,v.rating,v.comment,v.reply,v.created_at,c.full_name
                FROM reviews v JOIN booking_rooms br ON br.id=v.booking_room_id
                JOIN customers c ON c.id=v.customer_id
                WHERE v.status='PUBLISHED' AND br.room_type_id=?
                ORDER BY v.created_at DESC,v.id DESC LIMIT ? OFFSET ?
                """,
                        id,
                        req.getPageSize(),
                        req.getOffset());
        var content =
                rows.stream()
                        .map(
                                r ->
                                        new Review(
                                                r.id("id"),
                                                publicName(r.s("full_name")),
                                                r.n("rating"),
                                                r.s("comment"),
                                                r.s("reply"),
                                                r.time("created_at")))
                        .toList();
        long total = stats.id("total");
        return new Reviews(
                content,
                total,
                decimal(stats, "rating"),
                page,
                size,
                req.getOffset() + content.size() >= total);
    }

    public DiscoveryModels.Calendar calendar(
            long id, YearMonth month, LocalDate from, LocalDate to, int quantity) {
        room(id);
        LocalDate today = inventory.today(), horizon = inventory.horizon();
        if (month != null && (from != null || to != null))
            throw bad("Chọn tháng hoặc khoảng ngày.");
        if (from == null && to == null) {
            YearMonth selected = month == null ? YearMonth.from(today) : month;
            from = selected.atDay(1);
            to = selected.atEndOfMonth();
        }
        if (from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > 30
                || from.isBefore(today.minusYears(1))
                || to.isAfter(horizon.plusDays(31))
                || quantity < 1
                || quantity > 12) throw bad("Khoảng lịch không hợp lệ. Chọn tối đa 31 ngày.");
        LocalDate until = to.plusDays(1);
        var rateCalendar = pricing.discoveryCalendar(from, until);
        var rates =
                rateCalendar.rows().stream()
                        .filter(row -> row.roomTypeId() == id)
                        .findFirst()
                        .orElseThrow(() -> missing(id))
                        .cells()
                        .stream()
                        .collect(
                                Collectors.toMap(
                                        PricingModels.CalendarCell::date,
                                        PricingModels.CalendarCell::rate));
        var remaining = new HashMap<LocalDate, Integer>();
        // Compare inventory with physical rooms for each night, excluding floors and room claims.
        String physical =
                AVAILABLE_ROOMS.formatted(
                        "i.stay_date", "i.stay_date", "i.stay_date", "i.stay_date");
        db.rows(
                        """
                SELECT i.stay_date, i.sellable_rooms-i.held_rooms-i.reserved_rooms AS available,
                    (SELECT COUNT(*) %s AND r.room_type_id=i.room_type_id) AS physical_count
                FROM room_type_inventory i WHERE i.room_type_id=? AND i.stay_date>=? AND i.stay_date<?
                """
                                .formatted(physical),
                        id,
                        from,
                        until)
                .forEach(
                        r ->
                                remaining.put(
                                        r.date("stay_date"),
                                        Math.max(
                                                0,
                                                Math.min(
                                                        r.n("available"), r.n("physical_count")))));
        var days =
                from.datesUntil(until)
                        .map(
                                day -> {
                                    int count =
                                            day.isBefore(today) || !day.isBefore(horizon)
                                                    ? 0
                                                    : remaining.getOrDefault(day, 0);
                                    var price = rates.get(day);
                                    return new CalendarDay(
                                            day,
                                            count >= quantity && price.signum() > 0,
                                            count,
                                            price,
                                            day.getDayOfWeek().getValue() >= 6);
                                })
                        .toList();
        return new DiscoveryModels.Calendar(hotel().s("currency"), days);
    }

    private Map<Long, Integer> stock(Stay stay) {
        var physical =
                db
                        .rows(
                                "SELECT r.room_type_id,COUNT(*) AS count "
                                        + AVAILABLE_ROOMS.formatted("?", "?", "?", "?")
                                        + " GROUP BY r.room_type_id",
                                stay.checkIn(),
                                stay.checkOut().minusDays(1),
                                stay.checkIn(),
                                stay.checkOut().minusDays(1))
                        .stream()
                        .collect(Collectors.toMap(r -> r.id("room_type_id"), r -> r.n("count")));
        return db
                .rows(
                        """
                SELECT room_type_id, MIN(sellable_rooms-held_rooms-reserved_rooms) AS available
                FROM room_type_inventory WHERE stay_date>=? AND stay_date<?
                GROUP BY room_type_id HAVING COUNT(*)=?
                """,
                        stay.checkIn(),
                        stay.checkOut(),
                        nights(stay))
                .stream()
                .collect(
                        Collectors.toMap(
                                r -> r.id("room_type_id"),
                                r ->
                                        Math.max(
                                                0,
                                                Math.min(
                                                        physical.getOrDefault(
                                                                r.id("room_type_id"), 0),
                                                        r.n("available")))));
    }

    private Policy policy() {
        var r =
                db.one(
                        """
                SELECT c.id,c.name,c.description,c.customer_can_cancel,c.customer_cancel_deadline_hours,
                       d.amount_type,d.amount_value,d.payment_due_minutes
                FROM hotel_settings h JOIN cancellation_policies c ON c.id=h.default_cancellation_policy_id
                JOIN deposit_policies d ON d.id=h.default_deposit_policy_id WHERE h.id=1
                """);
        var tiers =
                db
                        .rows(
                                """
                SELECT min_hours_before_checkin,fee_type,fee_value FROM cancellation_policy_rules
                WHERE cancellation_policy_id=? ORDER BY min_hours_before_checkin DESC
                """,
                                r.id("id"))
                        .stream()
                        .map(
                                t ->
                                        new CancellationTier(
                                                t.n("min_hours_before_checkin"),
                                                t.s("fee_type"),
                                                decimal(t, "fee_value")))
                        .toList();
        return new Policy(
                r.s("name"),
                r.s("description"),
                Boolean.TRUE.equals(r.data().get("customer_can_cancel")),
                r.n("customer_cancel_deadline_hours"),
                tiers,
                r.s("amount_type"),
                decimal(r, "amount_value"),
                r.n("payment_due_minutes"));
    }

    private Row hotel() {
        return db.one("SELECT * FROM hotel_settings WHERE id=1");
    }

    private Row room(long id) {
        return db.rows(ACTIVE_TYPES + " AND t.id=?", id).stream()
                .findFirst()
                .orElseThrow(() -> missing(id));
    }

    private Map<Long, Set<String>> amenityCodes() {
        var result = new HashMap<Long, Set<String>>();
        db.rows(
                        """
                SELECT a.code, ra.room_type_id FROM room_type_amenities ra
                JOIN amenities a ON a.id=ra.amenity_id WHERE a.is_active=TRUE
                """)
                .forEach(
                        r ->
                                result.computeIfAbsent(
                                                r.id("room_type_id"), unused -> new HashSet<>())
                                        .add(r.s("code").toUpperCase(Locale.ROOT)));
        return result;
    }

    private Map<Long, Quote> quotes(List<Row> rooms, Stay stay) {
        return pricing.discoveryQuotes(
                rooms.stream()
                        .map(
                                r ->
                                        new PricingModels.Stay(
                                                r.id("id"),
                                                stay.checkIn(),
                                                stay.checkOut(),
                                                stay.roomsCount()))
                        .toList());
    }

    private PageResponse<RoomCard> cards(
            List<Row> rows,
            int page,
            int size,
            Stay stay,
            Map<Long, Quote> quotes,
            Map<Long, Integer> remaining) {
        Row h = hotel();
        return page(
                rows,
                page,
                size,
                r -> card(r, h.s("address"), h.s("currency"), stay, quotes, remaining));
    }

    private RoomCard card(
            Row r, Hotel h, Stay stay, Map<Long, Quote> quotes, Map<Long, Integer> remaining) {
        return card(r, h.address(), h.currency(), stay, quotes, remaining);
    }

    private RoomCard card(
            Row r,
            String address,
            String currency,
            Stay stay,
            Map<Long, Quote> quotes,
            Map<Long, Integer> remaining) {
        long id = r.id("id");
        var images =
                db
                        .rows(
                                "SELECT image_url FROM room_type_images WHERE room_type_id=? ORDER"
                                    + " BY sort_order,id",
                                id)
                        .stream()
                        .map(x -> x.s("image_url"))
                        .toList();
        var amenities =
                db
                        .rows(
                                """
                SELECT a.name FROM amenities a JOIN room_type_amenities ra ON ra.amenity_id=a.id
                WHERE ra.room_type_id=? AND a.is_active=TRUE ORDER BY a.name,a.id
                """,
                                id)
                        .stream()
                        .map(x -> x.s("name"))
                        .toList();
        Quote quote = quotes.get(id);
        Integer available = stay == null ? null : remaining.getOrDefault(id, 0);
        BigDecimal base = decimal(r, "base_price");
        return new RoomCard(
                id,
                r.s("code"),
                r.s("name"),
                typeCode(r.s("code")),
                r.s("description"),
                address,
                base,
                quote == null ? base : average(quote, stay),
                quote == null ? null : quote.roomSubtotal(),
                quote == null ? null : quote.taxAmount(),
                quote == null ? null : quote.totalAmount(),
                quote == null ? null : quote.depositRequired(),
                currency,
                decimal(r, "rating"),
                r.id("review_count"),
                r.money("area_sqm"),
                r.s("bed_description"),
                r.n("max_adults"),
                r.n("max_children"),
                r.n("max_occupancy"),
                available,
                stay != null
                        && (available < stay.roomsCount()
                                || quote == null
                                || quote.roomSubtotal().signum() <= 0),
                stay == null || fits(r, stay),
                images,
                amenities);
    }

    private static BigDecimal decimal(Row row, String column) {
        return new BigDecimal(row.s(column));
    }

    private static LocalDate optionalDate(Row row, String column) {
        return row.s(column) == null ? null : row.date(column);
    }

    private static String publicName(String fullName) {
        String[] words = fullName.strip().split("\\s+");
        return words.length == 1
                ? words[0]
                : words[words.length - 1] + " " + words[0].substring(0, 1) + ".";
    }

    private static String typeCode(String code) {
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "STD" -> "STANDARD";
            case "SUP" -> "SUPERIOR";
            case "DLX" -> "DELUXE";
            case "SUI" -> "SUITE";
            default -> code.toUpperCase(Locale.ROOT);
        };
    }

    private static Set<String> codes(String value, boolean type) {
        if (value == null || value.isBlank()) return Set.of();
        if (value.length() > 800) throw bad("Bộ lọc vượt giới hạn.");
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> type ? typeCode(s) : s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static String fold(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd');
    }

    private static long nights(Stay stay) {
        return ChronoUnit.DAYS.between(stay.checkIn(), stay.checkOut());
    }

    private static BigDecimal average(Quote quote, Stay stay) {
        return quote.roomSubtotal()
                .divide(
                        BigDecimal.valueOf(nights(stay) * stay.roomsCount()),
                        0,
                        RoundingMode.HALF_UP);
    }

    private static boolean fits(Row row, Stay stay) {
        return row.n("max_adults") * stay.roomsCount() >= stay.adults()
                && row.n("max_children") * stay.roomsCount() >= stay.children()
                && row.n("max_occupancy") * stay.roomsCount() >= stay.adults() + stay.children();
    }

    private void validate(Stay stay) {
        if (stay.checkIn() == null
                || stay.checkOut() == null
                || stay.checkIn().isBefore(inventory.today())
                || !stay.checkOut().isAfter(stay.checkIn())
                || stay.checkOut().isAfter(inventory.horizon())
                || nights(stay) > 366
                || stay.roomsCount() < 1
                || stay.roomsCount() > 12
                || nights(stay) * stay.roomsCount() > 3660
                || stay.adults() < stay.roomsCount()
                || stay.adults() > 100
                || stay.children() < 0
                || stay.children() > 99)
            throw bad(
                    "Ngày lưu trú hoặc số khách không hợp lệ. Mỗi phòng cần ít nhất một người"
                        + " lớn.");
    }

    private static void checkPrice(BigDecimal min, BigDecimal max, BigDecimal rating) {
        if ((min != null && min.signum() < 0)
                || (max != null && max.signum() < 0)
                || (min != null && max != null && max.compareTo(min) < 0)
                || (rating != null
                        && (rating.signum() < 0 || rating.compareTo(BigDecimal.valueOf(5)) > 0)))
            throw bad("Khoảng giá hoặc mức đánh giá không hợp lệ.");
    }

    private static Comparator<Row> sort(String value, Function<Row, BigDecimal> price) {
        Comparator<Row> order =
                switch (value) {
                    case "PRICE_ASC" -> Comparator.comparing(price);
                    case "PRICE_DESC" -> Comparator.comparing(price).reversed();
                    case "RATING_DESC" -> Comparator.<Row, BigDecimal>comparing(
                                    r -> decimal(r, "rating"))
                            .reversed()
                            .thenComparing(
                                    Comparator.comparingLong((Row r) -> r.id("review_count"))
                                            .reversed());
                    case "POPULAR" -> Comparator.comparingLong((Row r) -> r.id("booking_count"))
                            .reversed();
                    case "NAME_ASC" -> Comparator.comparing(
                            r -> r.s("name"), String.CASE_INSENSITIVE_ORDER);
                    case "TYPE_ASC" -> Comparator.comparing(r -> typeCode(r.s("code")));
                    default -> throw bad("Tiêu chí sắp xếp không hợp lệ.");
                };
        return order.thenComparingLong(r -> r.id("id"));
    }

    private static PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 50)
            throw bad("Phân trang không hợp lệ. Kích thước từ 1 đến 50.");
        return PageRequest.of(page, size);
    }

    private static <S, T> PageResponse<T> page(
            List<S> rows, int page, int size, Function<S, T> mapper) {
        var request = pageRequest(page, size);
        int start = (int) Math.min(request.getOffset(), rows.size());
        int end = Math.min(start + size, rows.size());
        return PageResponse.from(
                new PageImpl<>(rows.subList(start, end), request, rows.size()), mapper);
    }

    private static BusinessException bad(String message) {
        return new BusinessException(message);
    }

    private static ResourceNotFoundException missing(long id) {
        return new ResourceNotFoundException("Room type", id);
    }
}
