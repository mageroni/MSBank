package com.msbank.auth.application;

import com.msbank.auth.config.AuthProperties;
import com.msbank.auth.domain.RefreshToken;
import com.msbank.auth.infrastructure.jpa.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Issues, rotates, and revokes opaque refresh tokens (256-bit random, SHA-256 hashed). */
@Service
public class RefreshTokenService {

    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenRepository repo;
    private final AuthProperties props;

    public RefreshTokenService(RefreshTokenRepository repo, AuthProperties props) {
        this.repo = repo;
        this.props = props;
    }

    public record IssuedToken(String rawToken, RefreshToken entity) {}

    /** Issue a brand-new token in a new rotation family. */
    @Transactional
    public IssuedToken issueNew(UUID userId) {
        return issue(userId, UUID.randomUUID());
    }

    @Transactional
    public IssuedToken issue(UUID userId, UUID familyId) {
        String raw = randomToken();
        RefreshToken t = new RefreshToken();
        t.setUserId(userId);
        t.setTokenHash(sha256(raw));
        t.setFamilyId(familyId);
        t.setIssuedAt(Instant.now());
        t.setExpiresAt(Instant.now().plusSeconds(props.jwt().refreshTtlSeconds()));
        repo.save(t);
        return new IssuedToken(raw, t);
    }

    /**
     * Rotate the supplied refresh token. On reuse of an already-revoked token, the
     * entire family is revoked (replay detection).
     *
     * @return the new IssuedToken
     * @throws SecurityException if the token is unknown, revoked, or expired
     */
    @Transactional
    public IssuedToken rotate(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken current = repo.findByTokenHash(hash)
                .orElseThrow(() -> new SecurityException("Unknown refresh token"));

        if (current.isRevoked()) {
            // Reuse of revoked token → kill the whole family
            repo.revokeFamily(current.getFamilyId(), Instant.now());
            throw new SecurityException("Refresh token reuse detected; family revoked");
        }
        if (!current.isActive(Instant.now())) {
            throw new SecurityException("Refresh token expired");
        }

        IssuedToken next = issue(current.getUserId(), current.getFamilyId());
        current.setRevoked(true);
        current.setRevokedAt(Instant.now());
        current.setReplacedBy(next.entity().getId());
        return next;
    }

    @Transactional
    public void revoke(String rawToken) {
        Optional<RefreshToken> found = repo.findByTokenHash(sha256(rawToken));
        found.ifPresent(t -> {
            t.setRevoked(true);
            t.setRevokedAt(Instant.now());
            repo.revokeFamily(t.getFamilyId(), Instant.now());
        });
    }

    private static String randomToken() {
        byte[] buf = new byte[32]; // 256 bits
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
