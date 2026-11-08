package com.example.hotelmanagement.modules.auth.repository;

import com.example.hotelmanagement.modules.auth.entity.RefreshToken;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @EntityGraph(attributePaths = {"user", "user.roles"})
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RefreshToken rt where rt.tokenHash=:hash")
    Optional<RefreshToken> lockByTokenHash(@Param("hash") String hash);

    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.user.id = :userId AND"
                + " rt.revokedAt IS NULL")
    int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(
            "UPDATE RefreshToken rt SET rt.revokedAt = :now WHERE rt.familyId = :familyId AND"
                + " rt.revokedAt IS NULL")
    int revokeAllActiveByFamilyId(@Param("familyId") String familyId, @Param("now") Instant now);
}
