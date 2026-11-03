package com.example.hotelmanagement.modules.hotel.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "hotel_settings")
public class HotelSetting {
    @Id private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(length = 30)
    private String hotline;

    @Column(length = 150)
    private String email;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(nullable = false, length = 60)
    private String timezone;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "standard_check_in", nullable = false)
    private java.time.LocalTime standardCheckIn;

    @Column(name = "standard_check_out", nullable = false)
    private java.time.LocalTime standardCheckOut;

    @Column(name = "booking_hold_minutes", nullable = false)
    private Integer bookingHoldMinutes;

    @Column(name = "default_tax_percent", nullable = false, precision = 5, scale = 2)
    private java.math.BigDecimal defaultTaxPercent;

    @Column(name = "default_deposit_policy_id", nullable = false)
    private Long defaultDepositPolicyId;

    @Column(name = "default_cancellation_policy_id", nullable = false)
    private Long defaultCancellationPolicyId;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private java.time.Instant updatedAt;
}
