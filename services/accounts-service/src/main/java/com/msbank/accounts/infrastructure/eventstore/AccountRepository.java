package com.msbank.accounts.infrastructure.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msbank.accounts.domain.AccountAggregate;
import com.msbank.accounts.domain.AccountEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Loads aggregates from the event store, optionally accelerated by snapshots.
 * Save persists events with optimistic concurrency on the expected next sequence.
 */
@Repository
public class AccountRepository {

    private final EventStore eventStore;
    private final ObjectMapper objectMapper;
    private final int snapshotInterval;

    public AccountRepository(EventStore eventStore, ObjectMapper objectMapper,
                             @Value("${accounts.snapshot.interval:50}") int snapshotInterval) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.snapshotInterval = snapshotInterval;
    }

    public Optional<AccountAggregate> load(UUID id) {
        Optional<SnapshotRecord> snap = eventStore.latestSnapshot(id);
        AccountAggregate agg;
        long fromSeq;
        if (snap.isPresent()) {
            try {
                AccountAggregate.Snapshot s = objectMapper.readValue(snap.get().stateJson(), AccountAggregate.Snapshot.class);
                agg = AccountAggregate.fromSnapshot(s);
                fromSeq = snap.get().sequence() + 1;
            } catch (Exception e) {
                throw new IllegalStateException("snapshot deserialize failed", e);
            }
        } else {
            agg = new AccountAggregate();
            fromSeq = 1L;
        }
        List<StoredEvent> tail = eventStore.loadStream(id, fromSeq);
        if (tail.isEmpty() && snap.isEmpty()) return Optional.empty();
        for (StoredEvent se : tail) agg.apply(se.event());
        return Optional.of(agg);
    }

    /** Save new events. {@code expectedNextSequence} is the version after which the new events apply. */
    public List<StoredEvent> save(UUID aggregateId, long expectedNextSequence,
                                  List<AccountEvent> newEvents, String correlationId) {
        if (newEvents.isEmpty()) return List.of();
        List<StoredEvent> stored = eventStore.append(aggregateId, expectedNextSequence, newEvents, correlationId);
        long finalSeq = expectedNextSequence + newEvents.size() - 1;
        if (snapshotInterval > 0 && finalSeq % snapshotInterval == 0) {
            // Best-effort: reload + snapshot. Failure here must not break the write.
            try {
                load(aggregateId).ifPresent(agg -> {
                    try {
                        String stateJson = objectMapper.writeValueAsString(agg.snapshot());
                        eventStore.saveSnapshot(aggregateId, agg.version(), stateJson);
                    } catch (Exception ignored) { /* snapshot best-effort */ }
                });
            } catch (Exception ignored) {
                // snapshotting is an optimisation; never fail the command
            }
        }
        return stored;
    }
}
