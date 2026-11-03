package com.example.hotelmanagement.modules.pricing.repository;

import com.example.hotelmanagement.modules.pricing.entity.RateRule;

import org.springframework.data.jpa.repository.*;

public interface RateRuleRepository extends JpaRepository<RateRule, Long> {
    boolean existsByCodeIgnoreCase(String code);
}
