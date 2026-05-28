package com.msbank.accounts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class Dtos {

    public record Money(@NotNull @Positive Long amount,
                        @NotBlank @Size(min = 3, max = 3) String currency) {}

    public record OpenAccountRequest(@NotBlank String accountType,
                                     @NotBlank @Size(min = 3, max = 3) String currency,
                                     String nickname) {}

    public record MoneyOpRequest(@NotNull UUID idempotencyKey,
                                 @NotNull @Valid Money money,
                                 String description) {}

    public record ReserveRequest(@NotNull UUID reservationId,
                                 @NotNull UUID transactionId,
                                 @NotNull @Valid Money money) {}

    public record FreezeRequest(String reason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountResponse(UUID id, UUID customerId, String accountType, String status,
                                  long balance, Long availableBalance, String currency, String nickname,
                                  long version, Instant createdAt, Instant updatedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DomainEventResponse(UUID eventId, String eventType, UUID aggregateId,
                                      long sequence, Instant occurredAt,
                                      Object payload, Object metadata) {}

    private Dtos() {}
}
