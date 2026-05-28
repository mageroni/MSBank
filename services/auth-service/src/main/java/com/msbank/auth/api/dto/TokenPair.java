package com.msbank.auth.api.dto;

public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {}
