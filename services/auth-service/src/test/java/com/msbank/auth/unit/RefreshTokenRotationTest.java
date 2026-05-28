package com.msbank.auth.unit;

import com.msbank.auth.application.RefreshTokenService;
import com.msbank.auth.config.AuthProperties;
import com.msbank.auth.domain.RefreshToken;
import com.msbank.auth.infrastructure.jpa.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshTokenRotationTest {

    private RefreshTokenRepository repo;
    private RefreshTokenService svc;
    private final Map<String, RefreshToken> store = new HashMap<>();
    private final Map<UUID, RefreshToken> byId = new HashMap<>();

    @BeforeEach
    void setup() {
        store.clear();
        byId.clear();
        repo = mock(RefreshTokenRepository.class);
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            store.put(t.getTokenHash(), t);
            byId.put(t.getId(), t);
            return t;
        });
        when(repo.findByTokenHash(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        AtomicInteger revoked = new AtomicInteger();
        when(repo.revokeFamily(any(UUID.class), any(Instant.class))).thenAnswer(inv -> {
            UUID family = inv.getArgument(0);
            int n = 0;
            for (RefreshToken t : store.values()) {
                if (t.getFamilyId().equals(family) && !t.isRevoked()) {
                    t.setRevoked(true);
                    t.setRevokedAt(inv.getArgument(1));
                    n++;
                }
            }
            revoked.addAndGet(n);
            return n;
        });
        AuthProperties props = new AuthProperties(
                new AuthProperties.Jwt("https://i", "a", 60, 3600, "p", "u", "k1"),
                new AuthProperties.Outbox(500, 50, 8),
                new AuthProperties.Kafka("t"),
                new AuthProperties.RateLimit(10)
        );
        svc = new RefreshTokenService(repo, props);
    }

    @Test
    void rotationReplacesOldTokenAndKeepsFamily() {
        UUID userId = UUID.randomUUID();
        var first = svc.issueNew(userId);
        var second = svc.rotate(first.rawToken());

        assertThat(first.entity().isRevoked()).isTrue();
        assertThat(first.entity().getReplacedBy()).isEqualTo(second.entity().getId());
        assertThat(second.entity().getFamilyId()).isEqualTo(first.entity().getFamilyId());
        assertThat(second.entity().isRevoked()).isFalse();
    }

    @Test
    void replayOfRevokedTokenRevokesFamily() {
        UUID userId = UUID.randomUUID();
        var first = svc.issueNew(userId);
        var second = svc.rotate(first.rawToken());
        // try to reuse the original token → should detect reuse and revoke family
        assertThatThrownBy(() -> svc.rotate(first.rawToken()))
                .isInstanceOf(SecurityException.class);
        assertThat(second.entity().isRevoked()).isTrue();
    }

    @Test
    void unknownTokenRejected() {
        assertThatThrownBy(() -> svc.rotate("totally-bogus"))
                .isInstanceOf(SecurityException.class);
    }
}
