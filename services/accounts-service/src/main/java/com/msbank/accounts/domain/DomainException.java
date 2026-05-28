package com.msbank.accounts.domain;

/** Thrown when a command violates an invariant (e.g. withdrawing from a frozen account). */
public class DomainException extends RuntimeException {
    private final String code;
    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
