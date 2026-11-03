package com.example.hotelmanagement.modules.amenity.repository;

import com.example.hotelmanagement.modules.amenity.entity.Amenity;

import org.springframework.data.jpa.repository.*;

public interface AmenityRepository
        extends JpaRepository<Amenity, Long>, JpaSpecificationExecutor<Amenity> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
