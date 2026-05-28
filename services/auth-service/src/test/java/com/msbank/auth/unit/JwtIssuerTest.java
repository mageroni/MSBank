package com.msbank.auth.unit;

import com.msbank.auth.application.JwtIssuer;
import com.msbank.auth.config.AuthProperties;
import com.msbank.auth.domain.User;
import com.msbank.auth.infrastructure.security.RsaKeyProvider;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtIssuerTest {

    @TempDir Path tempDir;

    private JwtIssuer issuer;
    private AuthProperties props;

    @BeforeEach
    void setup() throws Exception {
        props = new AuthProperties(
                new AuthProperties.Jwt("https://issuer.test", "msbank", 60, 3600,
                        tempDir.resolve("priv.pem").toString(),
                        tempDir.resolve("pub.pem").toString(),
                        "kid-1"),
                new AuthProperties.Outbox(500, 50, 8),
                new AuthProperties.Kafka("user-events"),
                new AuthProperties.RateLimit(10)
        );
        RsaKeyProvider keys = new RsaKeyProvider(props);
        // invoke @PostConstruct manually
        var initMethod = RsaKeyProvider.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(keys);
        issuer = new JwtIssuer(keys, props);
    }

    @Test
    void issuesAndVerifiesToken() throws Exception {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("a@b.c");
        String token = issuer.issueAccessToken(u, List.of("CUSTOMER"));
        JWTClaimsSet claims = issuer.verify(token);
        assertThat(claims.getSubject()).isEqualTo(u.getId().toString());
        assertThat(claims.getStringClaim("email")).isEqualTo("a@b.c");
        assertThat(claims.getIssuer()).isEqualTo("https://issuer.test");
    }

    @Test
    void rejectsTamperedToken() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("a@b.c");
        String token = issuer.issueAccessToken(u, List.of("CUSTOMER"));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThatThrownBy(() -> issuer.verify(tampered)).isInstanceOf(SecurityException.class);
    }
}
