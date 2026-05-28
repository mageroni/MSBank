package com.msbank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountAggregateTest {

    private final UUID customerId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void openAccount_producesAccountOpenedEvent() {
        AccountAggregate agg = new AccountAggregate();
        List<AccountEvent> events = agg.handle(
                new AccountCommand.OpenAccount(accountId, customerId, "CHECKING", "USD", "primary"));
        assertThat(events).singleElement().isInstanceOf(AccountEvent.AccountOpened.class);
    }

    @Test
    void depositAndWithdraw_updateBalancesViaApplyOnly() {
        AccountAggregate agg = openedAccount();
        applyAll(agg, agg.handle(new AccountCommand.Deposit(accountId, 10_000L, "USD", UUID.randomUUID(), "salary")));
        applyAll(agg, agg.handle(new AccountCommand.Withdraw(accountId, 2_500L, "USD", UUID.randomUUID(), "atm")));
        assertThat(agg.balance()).isEqualTo(7_500L);
        assertThat(agg.availableBalance()).isEqualTo(7_500L);
    }

    @Test
    void withdraw_insufficientFunds_throws() {
        AccountAggregate agg = openedAccount();
        assertThatThrownBy(() ->
                agg.handle(new AccountCommand.Withdraw(accountId, 5_000L, "USD", UUID.randomUUID(), "x")))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void frozenAccount_rejectsDepositAndWithdraw() {
        AccountAggregate agg = openedAccount();
        applyAll(agg, agg.handle(new AccountCommand.Freeze(accountId, "fraud check")));
        assertThatThrownBy(() ->
                agg.handle(new AccountCommand.Deposit(accountId, 100L, "USD", UUID.randomUUID(), null)))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("FROZEN");
        assertThatThrownBy(() ->
                agg.handle(new AccountCommand.Withdraw(accountId, 50L, "USD", UUID.randomUUID(), null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deposit_idempotentOnIdempotencyKey() {
        AccountAggregate agg = openedAccount();
        UUID key = UUID.randomUUID();
        List<AccountEvent> first = agg.handle(new AccountCommand.Deposit(accountId, 100L, "USD", key, "x"));
        applyAll(agg, first);
        List<AccountEvent> replay = agg.handle(new AccountCommand.Deposit(accountId, 100L, "USD", key, "x"));
        assertThat(replay).isEmpty();
        assertThat(agg.balance()).isEqualTo(100L);
    }

    @Test
    void reservation_lifecycle_holdsThenCommits() {
        AccountAggregate agg = openedAccount();
        applyAll(agg, agg.handle(new AccountCommand.Deposit(accountId, 10_000L, "USD", UUID.randomUUID(), null)));
        UUID resvId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        applyAll(agg, agg.handle(new AccountCommand.ReserveFunds(accountId, resvId, txId, 3_000L, "USD")));
        assertThat(agg.balance()).isEqualTo(10_000L);
        assertThat(agg.availableBalance()).isEqualTo(7_000L);
        applyAll(agg, agg.handle(new AccountCommand.CommitReservation(accountId, resvId)));
        assertThat(agg.balance()).isEqualTo(7_000L);
        assertThat(agg.availableBalance()).isEqualTo(7_000L);
    }

    @Test
    void reservation_release_restoresAvailableBalance() {
        AccountAggregate agg = openedAccount();
        applyAll(agg, agg.handle(new AccountCommand.Deposit(accountId, 10_000L, "USD", UUID.randomUUID(), null)));
        UUID resvId = UUID.randomUUID();
        applyAll(agg, agg.handle(new AccountCommand.ReserveFunds(accountId, resvId, UUID.randomUUID(), 4_000L, "USD")));
        assertThat(agg.availableBalance()).isEqualTo(6_000L);
        applyAll(agg, agg.handle(new AccountCommand.ReleaseReservation(accountId, resvId)));
        assertThat(agg.availableBalance()).isEqualTo(10_000L);
        assertThat(agg.balance()).isEqualTo(10_000L);
    }

    @Test
    void rehydrate_reconstructsStateFromEventsOnly() {
        // Create a sequence of events, then rebuild a fresh aggregate from history.
        AccountAggregate live = openedAccount();
        List<AccountEvent> history = new ArrayList<>();
        history.add(AccountAggregate.rehydrate(List.of()).handle(
                new AccountCommand.OpenAccount(accountId, customerId, "CHECKING", "USD", "primary")).get(0));
        // Simulate the same history above
        AccountAggregate rebuilt = AccountAggregate.rehydrate(history);
        applyAll(live, live.handle(new AccountCommand.Deposit(accountId, 5_000L, "USD", UUID.randomUUID(), null)));
        applyAll(live, live.handle(new AccountCommand.Withdraw(accountId, 2_000L, "USD", UUID.randomUUID(), null)));
        // Replay full history into rebuilt
        AccountAggregate rebuiltFromAll = AccountAggregate.rehydrate(
                List.of(history.get(0),
                        new AccountEvent.Deposited(accountId, 5_000L, "USD", null, null, java.time.Instant.now()),
                        new AccountEvent.Withdrawn(accountId, 2_000L, "USD", null, null, java.time.Instant.now())));
        assertThat(rebuiltFromAll.balance()).isEqualTo(3_000L);
        assertThat(rebuiltFromAll.status()).isEqualTo(AccountAggregate.Status.ACTIVE);
        assertThat(rebuiltFromAll.version()).isEqualTo(3L);
        assertThat(rebuilt.id()).isEqualTo(accountId);
    }

    private AccountAggregate openedAccount() {
        AccountAggregate agg = new AccountAggregate();
        applyAll(agg, agg.handle(new AccountCommand.OpenAccount(accountId, customerId, "CHECKING", "USD", "primary")));
        return agg;
    }

    private static void applyAll(AccountAggregate agg, List<AccountEvent> events) {
        for (AccountEvent e : events) agg.apply(e);
    }
}
