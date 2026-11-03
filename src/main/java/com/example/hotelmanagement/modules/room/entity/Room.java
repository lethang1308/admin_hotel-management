package com.example.hotelmanagement.modules.room.entity;

import jakarta.persistence.*;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rooms")
public class Room extends com.example.hotelmanagement.common.audit.BaseAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_number", nullable = false, unique = true, length = 20)
    private String roomNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private com.example.hotelmanagement.modules.roomtype.entity.RoomType roomType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    private com.example.hotelmanagement.modules.floor.entity.Floor floor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoomStatus status;

    @Column(name = "sell_from", nullable = false)
    private java.time.LocalDate sellFrom;

    @Column(name = "sell_until")
    private java.time.LocalDate sellUntil;

    @Column(columnDefinition = "text")
    private String notes;

    @Version
    @Column(nullable = false)
    private int version;
}
