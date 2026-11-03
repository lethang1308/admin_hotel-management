package com.example.hotelmanagement.modules.policy.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "deposit_policies")
public class DepositPolicy {
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
    @Column(name = "amount_type", nullable = false, length = 20)
    private DepositAmountType amountType;

    @Column(name = "amount_value", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal amountValue;

    @Column(name = "payment_due_minutes", nullable = false)
    private int paymentDueMinutes;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;
}
