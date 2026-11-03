package com.example.hotelmanagement.modules.roomtype.repository;

import com.example.hotelmanagement.modules.roomtype.entity.RoomType;

import org.springframework.data.jpa.repository.*;

public interface RoomTypeRepository
        extends JpaRepository<RoomType, Long>, JpaSpecificationExecutor<RoomType> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
