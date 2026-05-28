package com.msbank.accounts.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msbank.accounts.application.AccountCommandService;
import com.msbank.accounts.application.AccountQueryService;
import com.msbank.accounts.domain.AccountCommand;
import com.msbank.accounts.infrastructure.eventstore.StoredEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountsController {

    private final AccountCommandService commandService;
    private final AccountQueryService queryService;
    private final ObjectMapper mapper;

    public AccountsController(AccountCommandService commandService,
                              AccountQueryService queryService,
                              ObjectMapper mapper) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Dtos.AccountResponse> open(@Valid @RequestBody Dtos.OpenAccountRequest req,
                                                     @AuthenticationPrincipal Jwt jwt,
                                                     HttpServletRequest http) {
        UUID customerId = customerIdFrom(jwt);
        UUID accountId = UUID.randomUUID();
        commandService.openAccount(
                new AccountCommand.OpenAccount(accountId, customerId, req.accountType(), req.currency(), req.nickname()),
                http.getHeader("X-Correlation-Id"));
        // Read-your-writes: pull from projection (eventually consistent). For 201, synthesise immediate view.
        Dtos.AccountResponse body = queryService.findById(accountId)
                .map(v -> new Dtos.AccountResponse(v.id(), v.customerId(), v.accountType(), v.status(),
                        v.balance(), v.availableBalance(), v.currency(), v.nickname(), v.version(),
                        v.createdAt(), v.updatedAt()))
                .orElseGet(() -> new Dtos.AccountResponse(accountId, customerId, req.accountType(), "ACTIVE",
                        0L, 0L, req.currency(), req.nickname(), 1L,
                        java.time.Instant.now(), java.time.Instant.now()));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + accountId)).body(body);
    }

    @GetMapping
    public List<Dtos.AccountResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(required = false) String status) {
        UUID customerId = customerIdFrom(jwt);
        return queryService.listForCustomer(customerId, status).stream()
                .map(v -> new Dtos.AccountResponse(v.id(), v.customerId(), v.accountType(), v.status(),
                        v.balance(), v.availableBalance(), v.currency(), v.nickname(), v.version(),
                        v.createdAt(), v.updatedAt()))
                .toList();
    }

    @GetMapping("/{accountId}")
    public Dtos.AccountResponse get(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        UUID customerId = customerIdFrom(jwt);
        var v = queryService.findById(accountId)
                .orElseThrow(() -> new com.msbank.accounts.domain.DomainException("NOT_FOUND", "account not found"));
        if (!v.customerId().equals(customerId))
            throw new com.msbank.accounts.domain.DomainException("FORBIDDEN", "not your account");
        return new Dtos.AccountResponse(v.id(), v.customerId(), v.accountType(), v.status(),
                v.balance(), v.availableBalance(), v.currency(), v.nickname(), v.version(),
                v.createdAt(), v.updatedAt());
    }

    @GetMapping("/{accountId}/events")
    public List<Dtos.DomainEventResponse> events(@PathVariable UUID accountId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID customerId = customerIdFrom(jwt);
        List<StoredEvent> stream = commandService.loadEvents(accountId, customerId);
        return stream.stream().map(se -> {
            Object payload;
            Object metadata;
            try {
                payload = mapper.readTree(se.payloadJson());
                metadata = mapper.readTree(se.metadataJson());
            } catch (Exception e) {
                payload = se.payloadJson();
                metadata = se.metadataJson();
            }
            return new Dtos.DomainEventResponse(se.eventId(), se.eventType(), se.aggregateId(),
                    se.sequence(), se.occurredAt(), payload, metadata);
        }).toList();
    }

    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<Void> deposit(@PathVariable UUID accountId,
                                        @Valid @RequestBody Dtos.MoneyOpRequest req,
                                        @AuthenticationPrincipal Jwt jwt,
                                        HttpServletRequest http) {
        commandService.deposit(new AccountCommand.Deposit(accountId, req.money().amount(),
                req.money().currency(), req.idempotencyKey(), req.description()),
                customerIdFrom(jwt), http.getHeader("X-Correlation-Id"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{accountId}/withdrawals")
    public ResponseEntity<Void> withdraw(@PathVariable UUID accountId,
                                         @Valid @RequestBody Dtos.MoneyOpRequest req,
                                         @AuthenticationPrincipal Jwt jwt,
                                         HttpServletRequest http) {
        commandService.withdraw(new AccountCommand.Withdraw(accountId, req.money().amount(),
                req.money().currency(), req.idempotencyKey(), req.description()),
                customerIdFrom(jwt), http.getHeader("X-Correlation-Id"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{accountId}/freeze")
    public ResponseEntity<Void> freeze(@PathVariable UUID accountId,
                                       @RequestBody(required = false) Dtos.FreezeRequest req,
                                       @AuthenticationPrincipal Jwt jwt,
                                       HttpServletRequest http) {
        String reason = req == null ? null : req.reason();
        commandService.freeze(new AccountCommand.Freeze(accountId, reason),
                customerIdFrom(jwt), http.getHeader("X-Correlation-Id"));
        return ResponseEntity.accepted().build();
    }

    private static UUID customerIdFrom(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
