package com.msbank.accounts.infrastructure.eventstore;

import com.msbank.accounts.domain.AccountEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only event store with optimistic concurrency on (aggregateId, sequence). */
public interface EventStore {

    /**
     * Append events to the stream starting at expectedNextSequence (1-based).
     * Throws {@link ConcurrencyConflictException} when the unique (aggregate_id, sequence)
     * constraint is violated by a concurrent writer.
     *
     * @param correlationId optional correlation id for outbox envelope metadata
     * @return the global sequence numbers assigned, in order.
     */
    List<StoredEvent> append(UUID aggregateId, long expectedNextSequence,
                             List<AccountEvent> events, String correlationId);

    /** Load all events for an aggregate, in order. */
    List<StoredEvent> loadStream(UUID aggregateId);

    /** Load events for an aggregate starting at sequence (inclusive). */
    List<StoredEvent> loadStream(UUID aggregateId, long fromSequence);

    /** Read events with global_seq strictly greater than {@code afterGlobalSeq}, capped at {@code limit}. */
    List<StoredEvent> readSince(long afterGlobalSeq, int limit);

    /** Snapshot support. */
    Optional<SnapshotRecord> latestSnapshot(UUID aggregateId);
    void saveSnapshot(UUID aggregateId, long sequence, String stateJson);
}
