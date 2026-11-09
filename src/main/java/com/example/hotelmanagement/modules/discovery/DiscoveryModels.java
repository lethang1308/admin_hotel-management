package com.example.hotelmanagement.modules.discovery;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DiscoveryModels {
    private DiscoveryModels() {}

    public record Stay(
            LocalDate checkIn, LocalDate checkOut, int adults, int children, int roomsCount) {}

    public record Search(
            Stay stay,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BigDecimal minRating,
            String type,
            String amenities,
            String sortBy,
            String city) {}

    public record Choice(String code, String name) {}

    public record Filters(
            List<Choice> roomTypes,
            List<Choice> amenities,
            String currency,
            String name,
            String address) {}

    public record Offer(
            String code,
            String name,
            String description,
            String discountType,
            BigDecimal discountValue,
            BigDecimal minRoomSubtotal,
            BigDecimal maxDiscountAmount,
            Instant expiresAt,
            LocalDate stayFrom,
            LocalDate stayUntil) {}

    public record Hotel(
            long id,
            String name,
            String description,
            String address,
            String phone,
            String email,
            String logoUrl,
            String bannerUrl,
            String currency,
            String checkInTime,
            String checkOutTime,
            LocalDate today,
            LocalDate lastCheckout,
            List<Offer> offers) {}

    public record RoomCard(
            long id,
            String code,
            String name,
            String type,
            String description,
            String city,
            BigDecimal basePrice,
            BigDecimal price,
            BigDecimal roomSubtotal,
            BigDecimal taxAmount,
            BigDecimal totalStayPrice,
            BigDecimal depositRequired,
            String currency,
            BigDecimal averageRating,
            long reviewCount,
            BigDecimal areaSqm,
            String bedDescription,
            int maxAdults,
            int maxChildren,
            int maxOccupancy,
            Integer availableRooms,
            boolean isSoldOut,
            boolean fitsGuests,
            List<String> photos,
            List<String> amenities) {}

    public record CancellationTier(int hoursBeforeCheckIn, String feeType, BigDecimal feeValue) {}

    public record Policy(
            String name,
            String description,
            boolean customerCanCancel,
            int cancellationDeadlineHours,
            List<CancellationTier> cancellationTiers,
            String depositType,
            BigDecimal depositValue,
            int paymentDueMinutes) {}

    public record Detail(Hotel hotel, List<RoomCard> rooms, Policy policy) {}

    public record CalendarDay(
            LocalDate date,
            boolean available,
            int remainingRooms,
            BigDecimal price,
            boolean isWeekend) {}

    public record Calendar(String currency, List<CalendarDay> days) {}

    public record Review(
            long id,
            String guestName,
            int rating,
            String comment,
            String reply,
            Instant createdAt) {}

    public record Reviews(
            List<Review> content,
            long totalElements,
            BigDecimal averageRating,
            int number,
            int size,
            boolean last) {}
}
