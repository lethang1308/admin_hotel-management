package com.example.hotelmanagement.modules.room.repository;

import com.example.hotelmanagement.modules.room.entity.RoomBlock;

import org.springframework.data.jpa.repository.*;

public interface RoomBlockRepository
        extends JpaRepository<RoomBlock, Long>, JpaSpecificationExecutor<RoomBlock> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from RoomBlock b where b.id=:id")
    java.util.Optional<RoomBlock> lockById(Long id);
}
