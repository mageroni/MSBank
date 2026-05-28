package com.msbank.accounts.domain;

import java.util.UUID;

public sealed interface AccountCommand
        permits AccountCommand.OpenAccount,
                AccountCommand.Deposit,
                AccountCommand.Withdraw,
                AccountCommand.Freeze,
                AccountCommand.ReserveFunds,
                AccountCommand.CommitReservation,
                AccountCommand.ReleaseReservation {

    record OpenAccount(UUID accountId, UUID customerId, String accountType,
                       String currency, String nickname) implements AccountCommand {}

    record Deposit(UUID accountId, long amount, String currency,
                   UUID idempotencyKey, String description) implements AccountCommand {}

    record Withdraw(UUID accountId, long amount, String currency,
                    UUID idempotencyKey, String description) implements AccountCommand {}

    record Freeze(UUID accountId, String reason) implements AccountCommand {}

    record ReserveFunds(UUID accountId, UUID reservationId, UUID transactionId,
                        long amount, String currency) implements AccountCommand {}

    record CommitReservation(UUID accountId, UUID reservationId) implements AccountCommand {}

    record ReleaseReservation(UUID accountId, UUID reservationId) implements AccountCommand {}
}
