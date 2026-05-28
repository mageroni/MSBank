package com.msbank.auth.infrastructure.security;

import com.msbank.auth.config.AuthProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Per-IP login rate limiter (Resilience4j). Configured as 10 req/min by default. */
@Component
public class LoginRateLimiter {

    private final RateLimiterRegistry registry;
    private final ConcurrentMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final RateLimiterConfig cfg;

    public LoginRateLimiter(AuthProperties props) {
        this.cfg = RateLimiterConfig.custom()
                .limitForPeriod(props.ratelimit().loginPerMinute())
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        this.registry = RateLimiterRegistry.of(cfg);
    }

    public void check(String ip) {
        String key = ip == null ? "unknown" : ip;
        RateLimiter rl = limiters.computeIfAbsent(key, k -> registry.rateLimiter(k, cfg));
        if (!rl.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(rl);
        }
    }
}
