package com.example.hotelmanagement.modules.room.repository;

import com.example.hotelmanagement.modules.room.entity.RoomStatusHistory;

import org.springframework.data.jpa.repository.*;

public interface RoomStatusHistoryRepository
        extends JpaRepository<RoomStatusHistory, Long>,
                JpaSpecificationExecutor<RoomStatusHistory> {}
