package com.msbank.auth.api.dto;

import com.msbank.auth.domain.Role;
import com.msbank.auth.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        List<String> roles,
        boolean mfaEnabled,
        Instant createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(
                u.getId(),
                u.getEmail(),
                u.getFirstName(),
                u.getLastName(),
                u.getRoles().stream().map(Role::getName).sorted().toList(),
                u.isMfaEnabled(),
                u.getCreatedAt()
        );
    }
}
