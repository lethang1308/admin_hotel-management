package com.example.hotelmanagement.modules.room.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "room_blocks")
public class RoomBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "maintenance_request_id")
    private Long maintenanceRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 30)
    private RoomBlockType blockType;

    @Column(name = "from_date", nullable = false)
    private java.time.LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private java.time.LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomBlockStatus status;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "released_by")
    private Long releasedBy;

    @Column(name = "released_at")
    private java.time.Instant releasedAt;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;
}
