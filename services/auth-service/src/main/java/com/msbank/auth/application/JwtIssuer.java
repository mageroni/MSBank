package com.msbank.auth.application;

import com.msbank.auth.config.AuthProperties;
import com.msbank.auth.domain.User;
import com.msbank.auth.infrastructure.security.RsaKeyProvider;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Signs access JWTs using the configured RSA key (RS256). */
@Component
public class JwtIssuer {

    private final RsaKeyProvider keys;
    private final AuthProperties props;

    public JwtIssuer(RsaKeyProvider keys, AuthProperties props) {
        this.keys = keys;
        this.props = props;
    }

    /** Issue a signed access token. Returns the compact JWS string. */
    public String issueAccessToken(User user, List<String> roles) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user.getId().toString())
                    .issuer(props.jwt().issuer())
                    .audience(props.jwt().audience())
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(props.jwt().accessTtlSeconds())))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("email", user.getEmail())
                    .claim("roles", roles)
                    .claim("mfa", user.isMfaEnabled())
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(keys.keyId())
                    .type(com.nimbusds.jose.JOSEObjectType.JWT)
                    .build();

            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(keys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    public long accessTtlSeconds() { return props.jwt().accessTtlSeconds(); }

    /** Parse + verify a signed JWT. Throws if invalid or expired. */
    public JWTClaimsSet verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(keys.publicKey()))) {
                throw new SecurityException("Invalid signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(now)) {
                throw new SecurityException("Token expired");
            }
            if (!props.jwt().issuer().equals(claims.getIssuer())) {
                throw new SecurityException("Bad issuer");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(props.jwt().audience())) {
                throw new SecurityException("Bad audience");
            }
            return claims;
        } catch (ParseException | JOSEException e) {
            throw new SecurityException("JWT verification failed: " + e.getMessage(), e);
        }
    }
}
