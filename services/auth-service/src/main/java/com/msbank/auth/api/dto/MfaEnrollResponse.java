package com.msbank.auth.api.dto;

public record MfaEnrollResponse(String secret, String otpAuthUri) {}
