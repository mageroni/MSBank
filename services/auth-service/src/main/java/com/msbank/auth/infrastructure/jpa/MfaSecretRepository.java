package com.msbank.auth.infrastructure.jpa;

import com.msbank.auth.domain.MfaSecret;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MfaSecretRepository extends JpaRepository<MfaSecret, UUID> {
    Optional<MfaSecret> findByUserId(UUID userId);
}
