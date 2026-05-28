package com.msbank.auth.api;

import com.msbank.auth.application.AuthService;
import com.msbank.auth.api.dto.LoginRequest;
import com.msbank.auth.api.dto.RefreshRequest;
import com.msbank.auth.api.dto.RegisterRequest;
import com.msbank.auth.api.dto.TokenPair;
import com.msbank.auth.api.dto.UserResponse;
import com.msbank.auth.infrastructure.security.AuthenticatedUser;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Public-facing REST endpoints implementing the auth.yaml OpenAPI contract. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final com.msbank.auth.infrastructure.security.LoginRateLimiter rateLimiter;

    public AuthController(AuthService auth, com.msbank.auth.infrastructure.security.LoginRateLimiter rateLimiter) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        rateLimiter.check(http.getRemoteAddr());
        return auth.login(req);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal,
                                       @RequestBody(required = false) RefreshRequest req) {
        UUID userId = principal != null ? principal.userId() : null;
        String refresh = req == null ? null : req.refreshToken();
        auth.logout(userId, refresh);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return auth.me(principal.userId());
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<com.msbank.auth.api.dto.Problem> rateLimited(RequestNotPermitted ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(org.springframework.http.MediaType.parseMediaType("application/problem+json"))
                .body(new com.msbank.auth.api.dto.Problem(
                        java.net.URI.create("about:blank"),
                        "Too Many Requests",
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        ex.getMessage(),
                        "/api/v1/auth/login",
                        null));
    }
}
