package com.msbank.accounts.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for all account domain events.
 * Events are pure facts about what happened — they never carry behaviour.
 * Each event records the aggregate it belongs to plus when it occurred.
 */
public sealed interface AccountEvent
        permits AccountEvent.AccountOpened,
                AccountEvent.Deposited,
                AccountEvent.Withdrawn,
                AccountEvent.Frozen,
                AccountEvent.Unfrozen,
                AccountEvent.Closed,
                AccountEvent.FundsReserved,
                AccountEvent.ReservationCommitted,
                AccountEvent.ReservationReleased {

    UUID aggregateId();
    Instant occurredAt();

    record AccountOpened(UUID aggregateId, UUID customerId, String accountType,
                         String currency, String nickname, Instant occurredAt) implements AccountEvent {}

    record Deposited(UUID aggregateId, long amount, String currency,
                     UUID idempotencyKey, String description, Instant occurredAt) implements AccountEvent {}

    record Withdrawn(UUID aggregateId, long amount, String currency,
                     UUID idempotencyKey, String description, Instant occurredAt) implements AccountEvent {}

    record Frozen(UUID aggregateId, String reason, Instant occurredAt) implements AccountEvent {}

    record Unfrozen(UUID aggregateId, Instant occurredAt) implements AccountEvent {}

    record Closed(UUID aggregateId, Instant occurredAt) implements AccountEvent {}

    record FundsReserved(UUID aggregateId, UUID reservationId, UUID transactionId,
                         long amount, String currency, Instant occurredAt) implements AccountEvent {}

    record ReservationCommitted(UUID aggregateId, UUID reservationId, UUID transactionId,
                                long amount, String currency, Instant occurredAt) implements AccountEvent {}

    record ReservationReleased(UUID aggregateId, UUID reservationId, UUID transactionId,
                               long amount, String currency, Instant occurredAt) implements AccountEvent {}
}
