package com.example.hotelmanagement.modules.policy.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "surcharge_policies")
public class SurchargePolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SurchargeKind kind;

    @Column(name = "room_type_id")
    private Long roomTypeId;

    @Column(name = "from_minutes")
    private Integer fromMinutes;

    @Column(name = "to_minutes")
    private Integer toMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_type", nullable = false, length = 20)
    private SurchargeAmountType amountType;

    @Column(name = "amount_value", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal amountValue;

    @Column(nullable = false)
    private int priority;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
