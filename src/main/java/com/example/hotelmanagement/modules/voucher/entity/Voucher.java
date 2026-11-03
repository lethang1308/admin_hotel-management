package com.example.hotelmanagement.modules.voucher.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "vouchers")
public class Voucher {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private VoucherDiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 15, scale = 2)
    private java.math.BigDecimal maxDiscountAmount;

    @Column(name = "min_room_subtotal", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal minRoomSubtotal;

    @Column(name = "starts_at", nullable = false)
    private java.time.Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private java.time.Instant expiresAt;

    @Column(name = "stay_from")
    private java.time.LocalDate stayFrom;

    @Column(name = "stay_until")
    private java.time.LocalDate stayUntil;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "max_uses_per_customer", nullable = false)
    private int maxUsesPerCustomer;

    @Column(name = "combinable_with_length_discount", nullable = false)
    private boolean combinableWithLengthDiscount;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "voucher_room_types", joinColumns = @JoinColumn(name = "voucher_id"))
    @Column(name = "room_type_id")
    private java.util.Set<Long> roomTypeIds = new java.util.LinkedHashSet<>();
}
