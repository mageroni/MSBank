package com.msbank.accounts.infrastructure.eventstore;

public class ConcurrencyConflictException extends RuntimeException {
    public ConcurrencyConflictException(String msg) { super(msg); }
    public ConcurrencyConflictException(String msg, Throwable cause) { super(msg, cause); }
}
