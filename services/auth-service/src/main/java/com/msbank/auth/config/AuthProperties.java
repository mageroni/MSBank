package com.msbank.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for the auth-service.
 *
 * @param jwt        JWT settings (issuer, audience, TTLs, RSA key paths).
 * @param outbox     Outbox polling configuration.
 * @param kafka      Kafka topic configuration.
 * @param ratelimit  Rate limiter configuration (login per IP).
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        Jwt jwt,
        Outbox outbox,
        Kafka kafka,
        RateLimit ratelimit
) {
    public record Jwt(
            String issuer,
            String audience,
            long accessTtlSeconds,
            long refreshTtlSeconds,
            String privateKeyPath,
            String publicKeyPath,
            String keyId
    ) {}

    public record Outbox(long pollIntervalMs, int batchSize, int maxAttempts) {}

    public record Kafka(String userEventsTopic) {}

    public record RateLimit(int loginPerMinute) {}
}
