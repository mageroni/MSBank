package com.msbank.accounts.api;

import com.msbank.accounts.domain.DomainException;
import com.msbank.accounts.infrastructure.eventstore.ConcurrencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 problem responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> domain(DomainException ex, HttpServletRequest req) {
        HttpStatus status = switch (ex.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "INSUFFICIENT_FUNDS" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "FROZEN", "CLOSED", "CURRENCY_MISMATCH", "INVALID_AMOUNT",
                 "RESERVATION_NOT_FOUND", "RESERVATION_COMMITTED", "RESERVATION_RELEASED",
                 "ALREADY_OPEN" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return build(status, ex.code(), ex.getMessage(), req);
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<ProblemDetail> concurrency(ConcurrencyConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> denied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage(), req);
    }

    private ResponseEntity<ProblemDetail> build(HttpStatus status, String code, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail == null ? code : detail);
        pd.setType(URI.create("https://msbank.local/problems/" + code.toLowerCase()));
        pd.setTitle(code);
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
