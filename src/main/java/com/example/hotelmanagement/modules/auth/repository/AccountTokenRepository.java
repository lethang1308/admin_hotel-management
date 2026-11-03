package com.example.hotelmanagement.modules.auth.repository;

import com.example.hotelmanagement.modules.auth.entity.AccountToken;

import org.springframework.data.jpa.repository.*;

import java.time.Instant;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {
    @Modifying
    @Query("update AccountToken t set t.usedAt=:now where t.userId=:userId and t.usedAt is null")
    int invalidateAll(Long userId, Instant now);
}
