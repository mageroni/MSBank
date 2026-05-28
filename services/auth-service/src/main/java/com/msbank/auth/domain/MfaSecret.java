package com.msbank.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Base32-encoded TOTP secret tied to a user. */
@Entity
@Table(name = "mfa_secrets")
@Getter
@Setter
@NoArgsConstructor
public class MfaSecret {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "secret_b32", nullable = false, length = 255)
    private String secretBase32;

    @Column(nullable = false)
    private boolean confirmed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
