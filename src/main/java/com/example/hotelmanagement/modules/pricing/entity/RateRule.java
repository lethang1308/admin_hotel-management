package com.example.hotelmanagement.modules.pricing.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "rate_rules")
public class RateRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RateRuleKind kind;

    @Column(name = "start_date", nullable = false)
    private java.time.LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private java.time.LocalDate endDate;

    @Column(name = "weekday_mask", nullable = false)
    private int weekdayMask;

    @Column(name = "min_nights", nullable = false)
    private int minNights;

    @Column(name = "max_nights")
    private Integer maxNights;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private RateValueType valueType;

    @Column(nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal value;

    @Column(nullable = false)
    private int priority;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "rate_rule_room_types",
            joinColumns = @JoinColumn(name = "rate_rule_id"))
    @Column(name = "room_type_id")
    private java.util.Set<Long> roomTypeIds = new java.util.LinkedHashSet<>();
}
