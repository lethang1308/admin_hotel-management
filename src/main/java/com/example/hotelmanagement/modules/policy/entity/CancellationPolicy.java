package com.example.hotelmanagement.modules.policy.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cancellation_policies")
public class CancellationPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "customer_can_cancel", nullable = false)
    private boolean customerCanCancel;

    @Column(name = "customer_cancel_deadline_hours", nullable = false)
    private int customerCancelDeadlineHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "no_show_fee_type", nullable = false, length = 20)
    private FeeType noShowFeeType;

    @Column(name = "no_show_fee_value", nullable = false, precision = 15, scale = 2)
    private java.math.BigDecimal noShowFeeValue;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minHoursBeforeCheckin DESC")
    private java.util.List<CancellationPolicyRule> rules = new java.util.ArrayList<>();
}
