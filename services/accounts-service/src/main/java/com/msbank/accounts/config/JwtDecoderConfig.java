package com.msbank.accounts.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * RS256 JWT decoder. JWKS URI defaults to {@code ${JWT_ISSUER}/.well-known/jwks.json}
 * but can be overridden with {@code JWT_JWKS_URI} for Docker/internal network resolution.
 */
@Configuration
public class JwtDecoderConfig {

    private final String issuer;
    private final String audience;
    private final String jwksUri;

    public JwtDecoderConfig(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                            @Value("${accounts.jwt.audience}") String audience,
                            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwksUriOverride) {
        this.issuer = issuer;
        this.audience = audience;
        this.jwksUri = jwksUriOverride.isBlank()
                ? issuer.replaceAll("/$", "") + "/.well-known/jwks.json"
                : jwksUriOverride;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            List<String> aud = jwt.getAudience();
            if (aud != null && aud.contains(audience)) return OAuth2TokenValidatorResult.success();
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_audience", "expected " + audience, null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator));
        return decoder;
    }
}

