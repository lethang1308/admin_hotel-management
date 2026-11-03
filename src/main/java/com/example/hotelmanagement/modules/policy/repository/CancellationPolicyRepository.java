package com.example.hotelmanagement.modules.policy.repository;

import com.example.hotelmanagement.modules.policy.entity.CancellationPolicy;

import org.springframework.data.jpa.repository.*;

public interface CancellationPolicyRepository extends JpaRepository<CancellationPolicy, Long> {
    boolean existsByCodeIgnoreCase(String code);
}
