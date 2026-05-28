package com.msbank.accounts.application;

import com.msbank.accounts.domain.AccountAggregate;
import com.msbank.accounts.domain.AccountCommand;
import com.msbank.accounts.domain.AccountEvent;
import com.msbank.accounts.domain.DomainException;
import com.msbank.accounts.infrastructure.eventstore.AccountRepository;
import com.msbank.accounts.infrastructure.eventstore.EventStore;
import com.msbank.accounts.infrastructure.eventstore.StoredEvent;
import com.msbank.accounts.infrastructure.reservation.ReservationStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write-side application service. Loads aggregate, dispatches command, saves new events,
 * updates side-tables (reservations) — all in one transaction so the outbox is consistent.
 */
@Service
public class AccountCommandService {

    private final AccountRepository repository;
    private final EventStore eventStore;
    private final ReservationStore reservationStore;

    public AccountCommandService(AccountRepository repository, EventStore eventStore,
                                 ReservationStore reservationStore) {
        this.repository = repository;
        this.eventStore = eventStore;
        this.reservationStore = reservationStore;
    }

    @Transactional
    public UUID openAccount(AccountCommand.OpenAccount cmd, String correlationId) {
        AccountAggregate agg = new AccountAggregate();
        List<AccountEvent> events = agg.handle(cmd);
        repository.save(cmd.accountId(), 1L, events, correlationId);
        return cmd.accountId();
    }

    @Transactional
    public void deposit(AccountCommand.Deposit cmd, UUID callerCustomerId, String correlationId) {
        AccountAggregate agg = loadOwned(cmd.accountId(), callerCustomerId);
        List<AccountEvent> events = agg.handle(cmd);
        if (events.isEmpty()) return; // idempotent replay
        repository.save(cmd.accountId(), agg.version() + 1, events, correlationId);
    }

    @Transactional
    public void withdraw(AccountCommand.Withdraw cmd, UUID callerCustomerId, String correlationId) {
        AccountAggregate agg = loadOwned(cmd.accountId(), callerCustomerId);
        List<AccountEvent> events = agg.handle(cmd);
        if (events.isEmpty()) return;
        repository.save(cmd.accountId(), agg.version() + 1, events, correlationId);
    }

    @Transactional
    public void freeze(AccountCommand.Freeze cmd, UUID callerCustomerId, String correlationId) {
        AccountAggregate agg = loadOwned(cmd.accountId(), callerCustomerId);
        List<AccountEvent> events = agg.handle(cmd);
        if (events.isEmpty()) return;
        repository.save(cmd.accountId(), agg.version() + 1, events, correlationId);
    }

    /** Reservation commands are called by internal (saga) callers — no JWT/customer check. */
    @Transactional
    public void reserve(AccountCommand.ReserveFunds cmd, String correlationId) {
        Optional<ReservationStore.Row> existing = reservationStore.find(cmd.reservationId());
        if (existing.isPresent()) return; // idempotent
        AccountAggregate agg = repository.load(cmd.accountId())
                .orElseThrow(() -> new DomainException("NOT_FOUND", "account does not exist"));
        List<AccountEvent> events = agg.handle(cmd);
        if (events.isEmpty()) return;
        repository.save(cmd.accountId(), agg.version() + 1, events, correlationId);
        reservationStore.insertHeld(cmd.reservationId(), cmd.accountId(), cmd.transactionId(), cmd.amount(), cmd.currency());
    }

    @Transactional
    public void commitReservation(UUID accountId, UUID reservationId, String correlationId) {
        ReservationStore.Row row = reservationStore.find(reservationId)
                .orElseThrow(() -> new DomainException("RESERVATION_NOT_FOUND", "unknown reservation"));
        if (row.status() == ReservationStore.Status.COMMITTED) return;
        if (row.status() == ReservationStore.Status.RELEASED)
            throw new DomainException("RESERVATION_RELEASED", "already released");
        AccountAggregate agg = repository.load(accountId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "account does not exist"));
        List<AccountEvent> events = agg.handle(new AccountCommand.CommitReservation(accountId, reservationId));
        repository.save(accountId, agg.version() + 1, events, correlationId);
        reservationStore.updateStatus(reservationId, ReservationStore.Status.COMMITTED);
    }

    @Transactional
    public void releaseReservation(UUID accountId, UUID reservationId, String correlationId) {
        ReservationStore.Row row = reservationStore.find(reservationId)
                .orElseThrow(() -> new DomainException("RESERVATION_NOT_FOUND", "unknown reservation"));
        if (row.status() == ReservationStore.Status.RELEASED) return;
        if (row.status() == ReservationStore.Status.COMMITTED)
            throw new DomainException("RESERVATION_COMMITTED", "already committed");
        AccountAggregate agg = repository.load(accountId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "account does not exist"));
        List<AccountEvent> events = agg.handle(new AccountCommand.ReleaseReservation(accountId, reservationId));
        repository.save(accountId, agg.version() + 1, events, correlationId);
        reservationStore.updateStatus(reservationId, ReservationStore.Status.RELEASED);
    }

    public List<StoredEvent> loadEvents(UUID accountId, UUID callerCustomerId) {
        AccountAggregate agg = loadOwned(accountId, callerCustomerId);
        return eventStore.loadStream(accountId);
    }

    private AccountAggregate loadOwned(UUID accountId, UUID callerCustomerId) {
        AccountAggregate agg = repository.load(accountId)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "account not found"));
        if (callerCustomerId != null && !callerCustomerId.equals(agg.customerId()))
            throw new DomainException("FORBIDDEN", "not your account");
        return agg;
    }
}
