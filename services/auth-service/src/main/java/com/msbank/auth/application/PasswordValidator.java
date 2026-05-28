package com.msbank.auth.application;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Enforces the password complexity policy: ≥12 chars, mixed case, digit, symbol.
 */
@Component
public class PasswordValidator {

    private static final int MIN_LENGTH = 12;
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SYMBOL = Pattern.compile("[^A-Za-z0-9]");

    /** @return null when valid, or a human-readable reason for rejection. */
    public String validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters";
        }
        if (!UPPER.matcher(password).find()) return "Password must contain an uppercase letter";
        if (!LOWER.matcher(password).find()) return "Password must contain a lowercase letter";
        if (!DIGIT.matcher(password).find()) return "Password must contain a digit";
        if (!SYMBOL.matcher(password).find()) return "Password must contain a symbol";
        return null;
    }

    public boolean isValid(String password) { return validate(password) == null; }
}
