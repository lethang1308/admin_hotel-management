package com.example.hotelmanagement.modules.room.repository;

import com.example.hotelmanagement.modules.room.entity.Room;

import org.springframework.data.jpa.repository.*;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {
    boolean existsByRoomNumberIgnoreCase(String number);

    boolean existsByRoomNumberIgnoreCaseAndIdNot(String number, Long id);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id=:id")
    java.util.Optional<Room> lockById(Long id);
}
