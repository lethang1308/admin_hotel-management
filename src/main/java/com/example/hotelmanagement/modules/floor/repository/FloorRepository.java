package com.example.hotelmanagement.modules.floor.repository;

import com.example.hotelmanagement.modules.floor.entity.Floor;

import org.springframework.data.jpa.repository.*;

public interface FloorRepository
        extends JpaRepository<Floor, Long>, JpaSpecificationExecutor<Floor> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
