package com.msbank.auth.application;

import com.msbank.auth.api.dto.LoginRequest;
import com.msbank.auth.api.dto.RegisterRequest;
import com.msbank.auth.api.dto.TokenPair;
import com.msbank.auth.api.dto.UserResponse;
import com.msbank.auth.api.error.ApiException;
import com.msbank.auth.domain.Role;
import com.msbank.auth.domain.User;
import com.msbank.auth.infrastructure.jpa.RoleRepository;
import com.msbank.auth.infrastructure.jpa.UserRepository;
import com.msbank.auth.infrastructure.outbox.OutboxWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Orchestrates registration, login, refresh and logout flows. */
@Service
public class AuthService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final PasswordValidator passwordValidator;
    private final JwtIssuer jwt;
    private final RefreshTokenService refreshTokens;
    private final MfaService mfa;
    private final OutboxWriter outbox;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
                       PasswordValidator passwordValidator, JwtIssuer jwt,
                       RefreshTokenService refreshTokens, MfaService mfa, OutboxWriter outbox) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.passwordValidator = passwordValidator;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.mfa = mfa;
        this.outbox = outbox;
    }

    @Transactional
    public UserResponse register(RegisterRequest req) {
        String complaint = passwordValidator.validate(req.password());
        if (complaint != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Weak password", complaint);
        }
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered", "An account with this email already exists");
        }

        User u = new User();
        u.setEmail(req.email().toLowerCase());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setFirstName(req.firstName());
        u.setLastName(req.lastName());
        Role customer = roles.findByName("CUSTOMER")
                .orElseGet(() -> roles.save(new Role("CUSTOMER")));
        u.setRoles(Set.of(customer));
        users.save(u);

        outbox.write("user", u.getId(), "UserRegistered", Map.of(
                "userId", u.getId().toString(),
                "email", u.getEmail(),
                "firstName", u.getFirstName(),
                "lastName", u.getLastName()
        ));

        return UserResponse.from(u);
    }

    @Transactional
    public TokenPair login(LoginRequest req) {
        User u = users.findByEmailIgnoreCase(req.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials", "Bad email or password"));
        if (!u.isEnabled() || !encoder.matches(req.password(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials", "Bad email or password");
        }
        if (u.isMfaEnabled() && !mfa.verify(u.getId(), req.totpCode())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "MFA required", "Valid TOTP code required");
        }

        List<String> roleNames = u.getRoles().stream().map(Role::getName).toList();
        String access = jwt.issueAccessToken(u, roleNames);
        RefreshTokenService.IssuedToken refresh = refreshTokens.issueNew(u.getId());

        outbox.write("user", u.getId(), "UserLoggedIn", Map.of(
                "userId", u.getId().toString(),
                "email", u.getEmail(),
                "firstName", u.getFirstName(),
                "lastName", u.getLastName()
        ));

        return new TokenPair(access, refresh.rawToken(), "Bearer", jwt.accessTtlSeconds());
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshTokenService.IssuedToken rotated;
        try {
            rotated = refreshTokens.rotate(rawRefreshToken);
        } catch (SecurityException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", e.getMessage());
        }
        User u = users.findById(rotated.entity().getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid user", "User no longer exists"));
        List<String> roleNames = u.getRoles().stream().map(Role::getName).toList();
        String access = jwt.issueAccessToken(u, roleNames);
        return new TokenPair(access, rotated.rawToken(), "Bearer", jwt.accessTtlSeconds());
    }

    @Transactional
    public void logout(UUID userId, String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.revoke(rawRefreshToken);
        }
    }

    public UserResponse me(UUID userId) {
        User u = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "Unknown user", "User not found"));
        return UserResponse.from(u);
    }
}
