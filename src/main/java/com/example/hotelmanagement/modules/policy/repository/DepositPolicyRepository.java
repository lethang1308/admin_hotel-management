package com.example.hotelmanagement.modules.policy.repository;

import com.example.hotelmanagement.modules.policy.entity.DepositPolicy;

import org.springframework.data.jpa.repository.*;

public interface DepositPolicyRepository extends JpaRepository<DepositPolicy, Long> {
    boolean existsByCodeIgnoreCase(String code);
}
