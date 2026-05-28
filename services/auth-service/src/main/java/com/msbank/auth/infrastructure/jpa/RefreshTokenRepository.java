package com.msbank.auth.infrastructure.jpa;

import com.msbank.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revoked = true, t.revokedAt = :now where t.familyId = :familyId and t.revoked = false")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
