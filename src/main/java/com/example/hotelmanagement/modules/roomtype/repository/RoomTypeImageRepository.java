package com.example.hotelmanagement.modules.roomtype.repository;

import com.example.hotelmanagement.modules.roomtype.entity.RoomTypeImage;

import org.springframework.data.jpa.repository.*;

public interface RoomTypeImageRepository
        extends JpaRepository<RoomTypeImage, Long>, JpaSpecificationExecutor<RoomTypeImage> {
    java.util.List<RoomTypeImage> findByRoomType_IdOrderBySortOrderAscIdAsc(Long typeId);

    java.util.Optional<RoomTypeImage> findByIdAndRoomType_Id(Long id, Long typeId);
}
