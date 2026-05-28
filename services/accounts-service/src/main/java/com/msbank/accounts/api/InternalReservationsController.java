package com.msbank.accounts.api;

import com.msbank.accounts.application.AccountCommandService;
import com.msbank.accounts.domain.AccountCommand;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/internal/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalReservationsController {

    private final AccountCommandService commandService;

    public InternalReservationsController(AccountCommandService commandService) {
        this.commandService = commandService;
    }

    @Bulkhead(name = "reservations")
    @PostMapping("/{accountId}/reservations")
    public ResponseEntity<Void> reserve(@PathVariable UUID accountId,
                                        @Valid @RequestBody Dtos.ReserveRequest req,
                                        HttpServletRequest http) {
        commandService.reserve(new AccountCommand.ReserveFunds(accountId, req.reservationId(),
                req.transactionId(), req.money().amount(), req.money().currency()),
                http.getHeader("X-Correlation-Id"));
        return ResponseEntity.status(201).build();
    }

    @Bulkhead(name = "reservations")
    @PostMapping("/{accountId}/reservations/{reservationId}/commit")
    public ResponseEntity<Void> commit(@PathVariable UUID accountId,
                                       @PathVariable UUID reservationId,
                                       HttpServletRequest http) {
        commandService.commitReservation(accountId, reservationId, http.getHeader("X-Correlation-Id"));
        return ResponseEntity.ok().build();
    }

    @Bulkhead(name = "reservations")
    @PostMapping("/{accountId}/reservations/{reservationId}/release")
    public ResponseEntity<Void> release(@PathVariable UUID accountId,
                                        @PathVariable UUID reservationId,
                                        HttpServletRequest http) {
        commandService.releaseReservation(accountId, reservationId, http.getHeader("X-Correlation-Id"));
        return ResponseEntity.ok().build();
    }
}
