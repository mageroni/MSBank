package com.msbank.accounts.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root for a bank account.
 *
 * <h2>Event sourcing contract</h2>
 * <ul>
 *   <li>State is <em>only</em> mutated by {@link #apply(AccountEvent)}.</li>
 *   <li>Command handlers (handle*) return a list of new events; they never mutate.</li>
 *   <li>The application layer is responsible for: (1) appending events to the store,
 *       (2) calling apply() to advance in-memory state.</li>
 *   <li>{@link #rehydrate(java.util.List)} replays history to reach the current state —
 *       this is the property tested by the unit tests.</li>
 * </ul>
 */
public final class AccountAggregate {

    public enum Status { ACTIVE, FROZEN, CLOSED }

    private UUID id;
    private UUID customerId;
    private String accountType;
    private String currency;
    private String nickname;
    private Status status;
    private long balance;
    private long availableBalance;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // Idempotency caches replayed from history
    private final Set<UUID> seenMoneyIdempotencyKeys = new HashSet<>();
    private final Map<UUID, ReservationHold> heldReservations = new HashMap<>();

    public record ReservationHold(UUID transactionId, long amount) {}

    public AccountAggregate() {}

    // ---------------- Command handlers ----------------

    public List<AccountEvent> handle(AccountCommand.OpenAccount cmd) {
        if (id != null) throw new DomainException("ALREADY_OPEN", "account already opened");
        return List.of(new AccountEvent.AccountOpened(
                cmd.accountId(), cmd.customerId(), cmd.accountType(), cmd.currency(), cmd.nickname(), Instant.now()));
    }

    public List<AccountEvent> handle(AccountCommand.Deposit cmd) {
        ensureExists();
        ensureActive();
        ensureCurrency(cmd.currency());
        if (cmd.amount() <= 0) throw new DomainException("INVALID_AMOUNT", "amount must be positive");
        if (cmd.idempotencyKey() != null && seenMoneyIdempotencyKeys.contains(cmd.idempotencyKey())) {
            return List.of();
        }
        return List.of(new AccountEvent.Deposited(
                id, cmd.amount(), cmd.currency(), cmd.idempotencyKey(), cmd.description(), Instant.now()));
    }

    public List<AccountEvent> handle(AccountCommand.Withdraw cmd) {
        ensureExists();
        ensureActive();
        ensureCurrency(cmd.currency());
        if (cmd.amount() <= 0) throw new DomainException("INVALID_AMOUNT", "amount must be positive");
        if (cmd.idempotencyKey() != null && seenMoneyIdempotencyKeys.contains(cmd.idempotencyKey())) {
            return List.of();
        }
        if (availableBalance < cmd.amount())
            throw new DomainException("INSUFFICIENT_FUNDS", "available balance too low");
        return List.of(new AccountEvent.Withdrawn(
                id, cmd.amount(), cmd.currency(), cmd.idempotencyKey(), cmd.description(), Instant.now()));
    }

    public List<AccountEvent> handle(AccountCommand.Freeze cmd) {
        ensureExists();
        if (status == Status.FROZEN) return List.of();
        if (status == Status.CLOSED) throw new DomainException("CLOSED", "closed account");
        return List.of(new AccountEvent.Frozen(id, cmd.reason(), Instant.now()));
    }

    public List<AccountEvent> handle(AccountCommand.ReserveFunds cmd) {
        ensureExists();
        ensureActive();
        ensureCurrency(cmd.currency());
        if (cmd.amount() <= 0) throw new DomainException("INVALID_AMOUNT", "amount must be positive");
        // Idempotent on reservationId: if already held/committed/released, no-op.
        if (heldReservations.containsKey(cmd.reservationId())) return List.of();
        if (availableBalance < cmd.amount())
            throw new DomainException("INSUFFICIENT_FUNDS", "available balance too low");
        return List.of(new AccountEvent.FundsReserved(
                id, cmd.reservationId(), cmd.transactionId(), cmd.amount(), cmd.currency(), Instant.now()));
    }

    public List<AccountEvent> handle(AccountCommand.CommitReservation cmd) {
        ensureExists();
        ReservationHold h = heldReservations.get(cmd.reservationId());
        if (h == null) throw new DomainException("RESERVATION_NOT_FOUND", "unknown reservation");
        return List.of(new AccountEvent.ReservationCommitted(
                id, cmd.reservationId(), h.transactionId(), h.amount(), currency, Instant.now()));
    }

    public List<AccountEvent> handle(AccountCommand.ReleaseReservation cmd) {
        ensureExists();
        ReservationHold h = heldReservations.get(cmd.reservationId());
        if (h == null) throw new DomainException("RESERVATION_NOT_FOUND", "unknown reservation");
        return List.of(new AccountEvent.ReservationReleased(
                id, cmd.reservationId(), h.transactionId(), h.amount(), currency, Instant.now()));
    }

    // ---------------- State mutation (apply) ----------------

    public void apply(AccountEvent event) {
        Objects.requireNonNull(event, "event");
        switch (event) {
            case AccountEvent.AccountOpened e -> {
                this.id = e.aggregateId();
                this.customerId = e.customerId();
                this.accountType = e.accountType();
                this.currency = e.currency();
                this.nickname = e.nickname();
                this.status = Status.ACTIVE;
                this.balance = 0L;
                this.availableBalance = 0L;
                this.createdAt = e.occurredAt();
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.Deposited e -> {
                this.balance += e.amount();
                this.availableBalance += e.amount();
                if (e.idempotencyKey() != null) seenMoneyIdempotencyKeys.add(e.idempotencyKey());
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.Withdrawn e -> {
                this.balance -= e.amount();
                this.availableBalance -= e.amount();
                if (e.idempotencyKey() != null) seenMoneyIdempotencyKeys.add(e.idempotencyKey());
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.Frozen e -> {
                this.status = Status.FROZEN;
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.Unfrozen e -> {
                this.status = Status.ACTIVE;
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.Closed e -> {
                this.status = Status.CLOSED;
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.FundsReserved e -> {
                this.availableBalance -= e.amount();
                this.heldReservations.put(e.reservationId(), new ReservationHold(e.transactionId(), e.amount()));
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.ReservationCommitted e -> {
                this.balance -= e.amount();
                this.heldReservations.remove(e.reservationId());
                this.updatedAt = e.occurredAt();
            }
            case AccountEvent.ReservationReleased e -> {
                this.availableBalance += e.amount();
                this.heldReservations.remove(e.reservationId());
                this.updatedAt = e.occurredAt();
            }
        }
        this.version++;
    }

    public static AccountAggregate rehydrate(List<AccountEvent> events) {
        AccountAggregate a = new AccountAggregate();
        for (AccountEvent e : events) a.apply(e);
        return a;
    }

    // ---------------- Invariants ----------------

    private void ensureExists() {
        if (id == null) throw new DomainException("NOT_FOUND", "account does not exist");
    }
    private void ensureActive() {
        if (status == Status.FROZEN) throw new DomainException("FROZEN", "account is frozen");
        if (status == Status.CLOSED) throw new DomainException("CLOSED", "account is closed");
    }
    private void ensureCurrency(String c) {
        if (currency != null && !currency.equals(c))
            throw new DomainException("CURRENCY_MISMATCH", "currency mismatch");
    }

    // ---------------- Accessors ----------------

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public String accountType() { return accountType; }
    public String currency() { return currency; }
    public String nickname() { return nickname; }
    public Status status() { return status; }
    public long balance() { return balance; }
    public long availableBalance() { return availableBalance; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public List<UUID> heldReservationIds() { return new ArrayList<>(heldReservations.keySet()); }

    // Snapshot/restore (used by snapshot store)
    public record Snapshot(UUID id, UUID customerId, String accountType, String currency, String nickname,
                           Status status, long balance, long availableBalance, long version,
                           Instant createdAt, Instant updatedAt,
                           Set<UUID> seenIdempotencyKeys, Map<UUID, ReservationHold> heldReservations) {}

    public Snapshot snapshot() {
        return new Snapshot(id, customerId, accountType, currency, nickname, status,
                balance, availableBalance, version, createdAt, updatedAt,
                new HashSet<>(seenMoneyIdempotencyKeys), new HashMap<>(heldReservations));
    }

    public static AccountAggregate fromSnapshot(Snapshot s) {
        AccountAggregate a = new AccountAggregate();
        a.id = s.id; a.customerId = s.customerId; a.accountType = s.accountType;
        a.currency = s.currency; a.nickname = s.nickname; a.status = s.status;
        a.balance = s.balance; a.availableBalance = s.availableBalance; a.version = s.version;
        a.createdAt = s.createdAt; a.updatedAt = s.updatedAt;
        if (s.seenIdempotencyKeys != null) a.seenMoneyIdempotencyKeys.addAll(s.seenIdempotencyKeys);
        if (s.heldReservations != null) a.heldReservations.putAll(s.heldReservations);
        return a;
    }
}
