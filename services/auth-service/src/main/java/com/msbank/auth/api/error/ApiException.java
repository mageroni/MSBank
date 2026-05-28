package com.msbank.auth.api.error;

import org.springframework.http.HttpStatus;

/** Domain-meaningful API exception that the global handler turns into RFC7807. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String title;
    private final String detail;

    public ApiException(HttpStatus status, String title, String detail) {
        super(title + ": " + detail);
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }
    public String detail() { return detail; }
}
