package com.example.hotelmanagement.modules.discovery;

import static com.example.hotelmanagement.modules.discovery.DiscoveryModels.*;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.common.pagination.PageResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/hotels")
public class HotelPublicController {
    private final RoomDiscoveryService discovery;

    @GetMapping("/info")
    public ApiResponse<Hotel> info() {
        return ApiResponse.ok(discovery.info());
    }

    @GetMapping("/filters")
    public ApiResponse<Filters> filters() {
        return ApiResponse.ok(discovery.filters());
    }

    @GetMapping
    public ApiResponse<PageResponse<RoomCard>> featured(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(discovery.featured(page, size));
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<RoomCard>> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam(defaultValue = "2") int adults,
            @RequestParam(defaultValue = "0") int children,
            @RequestParam(defaultValue = "1") int roomsCount,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String amenities,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "PRICE_ASC") String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        var stay = new Stay(checkIn, checkOut, adults, children, roomsCount);
        return ApiResponse.ok(
                discovery.search(
                        new Search(
                                stay, minPrice, maxPrice, minRating, type, amenities, sortBy, city),
                        page,
                        size));
    }

    @GetMapping("/{id}/info")
    public ApiResponse<Detail> room(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate endDate,
            @RequestParam(defaultValue = "2") int adults,
            @RequestParam(defaultValue = "0") int children,
            @RequestParam(defaultValue = "1") int roomsCount) {
        return ApiResponse.ok(
                discovery.detail(id, new Stay(startDate, endDate, adults, children, roomsCount)));
    }

    @GetMapping("/{id}/reviews")
    public ApiResponse<Reviews> reviews(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size) {
        return ApiResponse.ok(discovery.reviews(id, page, size));
    }

    @GetMapping("/{id}/calendar")
    public ApiResponse<Calendar> calendar(
            @PathVariable long id,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @RequestParam(defaultValue = "1") int roomsCount) {
        return ApiResponse.ok(discovery.calendar(id, month, from, to, roomsCount));
    }
}
