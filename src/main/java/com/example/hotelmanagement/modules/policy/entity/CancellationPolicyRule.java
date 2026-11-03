package com.example.hotelmanagement.modules.policy.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cancellation_policy_rules")
public class CancellationPolicyRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "min_hours_before_checkin", nullable = false)
    private int minHoursBeforeCheckin;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_type", nullable = false, length = 20)
    private FeeType feeType;

    @Column(name = "fee_value", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal feeValue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cancellation_policy_id", nullable = false)
    private CancellationPolicy policy;
}
