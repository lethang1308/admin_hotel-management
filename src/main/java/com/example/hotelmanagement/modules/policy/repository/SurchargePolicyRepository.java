package com.example.hotelmanagement.modules.policy.repository;

import com.example.hotelmanagement.modules.policy.entity.SurchargePolicy;

import org.springframework.data.jpa.repository.*;

public interface SurchargePolicyRepository extends JpaRepository<SurchargePolicy, Long> {
    boolean existsByCodeIgnoreCase(String code);
}
