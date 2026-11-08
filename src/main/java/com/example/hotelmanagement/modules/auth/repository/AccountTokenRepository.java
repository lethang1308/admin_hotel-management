package com.example.hotelmanagement.modules.auth.repository;

import com.example.hotelmanagement.modules.auth.entity.AccountToken;

import org.springframework.data.jpa.repository.*;

import java.time.Instant;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {
    java.util.Optional<AccountToken> findByTokenHashAndPurpose(String hash, String purpose);

    long countByUserIdAndPurposeAndCreatedAtAfter(Long userId, String purpose, Instant cutoff);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AccountToken t where t.id=:id")
    @QueryHints(
            @jakarta.persistence.QueryHint(
                    name = "jakarta.persistence.cache.retrieveMode",
                    value = "BYPASS"))
    java.util.Optional<AccountToken> lockById(Long id);

    @Modifying
    @Query(
            "update AccountToken t set t.usedAt=:now where t.userId=:userId and t.purpose=:purpose"
                + " and t.usedAt is null")
    int invalidatePurpose(Long userId, String purpose, Instant now);

    @Modifying
    @Query("update AccountToken t set t.usedAt=:now where t.userId=:userId and t.usedAt is null")
    int invalidateAll(Long userId, Instant now);
}
