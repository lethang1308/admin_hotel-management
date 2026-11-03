package com.example.hotelmanagement.modules.hotel.repository;

import com.example.hotelmanagement.modules.hotel.entity.HotelSetting;

import org.springframework.data.jpa.repository.*;

public interface HotelSettingRepository
        extends JpaRepository<HotelSetting, Long>, JpaSpecificationExecutor<HotelSetting> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from HotelSetting h where h.id=1")
    java.util.Optional<HotelSetting> lockSingleton();
}
