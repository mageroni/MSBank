package com.msbank.auth.infrastructure.security;

import java.util.List;
import java.util.UUID;

/** Principal injected by the JWT filter into the Spring SecurityContext. */
public record AuthenticatedUser(UUID userId, String email, List<String> roles) {
}
