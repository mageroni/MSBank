package com.msbank.accounts.infrastructure.eventstore;

import com.msbank.accounts.domain.AccountEvent;
import com.msbank.accounts.infrastructure.outbox.OutboxWriter;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed event store.
 * Append + outbox row are written inside the same transaction (caller provides the @Transactional
 * boundary in the application layer).
 */
@Repository
public class JdbcEventStore implements EventStore {

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO events (aggregate_id, sequence, event_id, event_type, payload, metadata, occurred_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """;
    private static final String SELECT_STREAM = """
            SELECT aggregate_id, sequence, event_id, event_type, payload, metadata, occurred_at, global_seq
            FROM events WHERE aggregate_id = ? AND sequence >= ? ORDER BY sequence
            """;
    private static final String SELECT_SINCE = """
            SELECT aggregate_id, sequence, event_id, event_type, payload, metadata, occurred_at, global_seq
            FROM events WHERE global_seq > ? ORDER BY global_seq LIMIT ?
            """;
    private static final String SELECT_SNAPSHOT = """
            SELECT aggregate_id, sequence, state, taken_at FROM snapshots WHERE aggregate_id = ?
            """;
    private static final String UPSERT_SNAPSHOT = """
            INSERT INTO snapshots (aggregate_id, sequence, state, taken_at) VALUES (?, ?, ?::jsonb, now())
            ON CONFLICT (aggregate_id) DO UPDATE SET sequence = EXCLUDED.sequence,
                                                     state = EXCLUDED.state,
                                                     taken_at = EXCLUDED.taken_at
            """;

    private final JdbcTemplate jdbc;
    private final EventJsonMapper jsonMapper;
    private final OutboxWriter outboxWriter;

    public JdbcEventStore(JdbcTemplate jdbc, EventJsonMapper jsonMapper, OutboxWriter outboxWriter) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.outboxWriter = outboxWriter;
    }

    @Override
    public List<StoredEvent> append(UUID aggregateId, long expectedNextSequence,
                                    List<AccountEvent> events, String correlationId) {
        List<StoredEvent> stored = new ArrayList<>(events.size());
        long seq = expectedNextSequence;
        for (AccountEvent event : events) {
            UUID eventId = UUID.randomUUID();
            String type = jsonMapper.typeName(event);
            String payload = jsonMapper.toJson(event);
            String metadata = correlationId == null ? "{}" : "{\"correlationId\":\"" + correlationId + "\"}";
            Timestamp ts = Timestamp.from(event.occurredAt());
            final long sequence = seq;
            try {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(INSERT_EVENT_SQL, new String[]{"global_seq"});
                    ps.setObject(1, aggregateId);
                    ps.setLong(2, sequence);
                    ps.setObject(3, eventId);
                    ps.setString(4, type);
                    ps.setString(5, payload);
                    ps.setString(6, metadata);
                    ps.setTimestamp(7, ts);
                    return ps;
                }, kh);
                Number gs = (Number) kh.getKeys().get("global_seq");
                long globalSeq = gs == null ? 0L : gs.longValue();
                stored.add(new StoredEvent(aggregateId, sequence, eventId, type, event, payload, metadata, event.occurredAt(), globalSeq));
                outboxWriter.write(eventId, event, correlationId, null);
            } catch (DuplicateKeyException dup) {
                throw new ConcurrencyConflictException(
                        "concurrency conflict on " + aggregateId + " seq " + sequence, dup);
            }
            seq++;
        }
        return stored;
    }

    @Override
    public List<StoredEvent> loadStream(UUID aggregateId) {
        return loadStream(aggregateId, 1L);
    }

    @Override
    public List<StoredEvent> loadStream(UUID aggregateId, long fromSequence) {
        return jdbc.query(SELECT_STREAM, this::map, aggregateId, fromSequence);
    }

    @Override
    public List<StoredEvent> readSince(long afterGlobalSeq, int limit) {
        return jdbc.query(SELECT_SINCE, this::map, afterGlobalSeq, limit);
    }

    @Override
    public Optional<SnapshotRecord> latestSnapshot(UUID aggregateId) {
        List<SnapshotRecord> list = jdbc.query(SELECT_SNAPSHOT,
                (rs, n) -> new SnapshotRecord(
                        (UUID) rs.getObject("aggregate_id"),
                        rs.getLong("sequence"),
                        rs.getString("state"),
                        rs.getTimestamp("taken_at").toInstant()),
                aggregateId);
        return list.stream().findFirst();
    }

    @Override
    public void saveSnapshot(UUID aggregateId, long sequence, String stateJson) {
        jdbc.update(UPSERT_SNAPSHOT, aggregateId, sequence, stateJson);
    }

    private StoredEvent map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        UUID aggregateId = (UUID) rs.getObject("aggregate_id");
        long sequence = rs.getLong("sequence");
        UUID eventId = (UUID) rs.getObject("event_id");
        String type = rs.getString("event_type");
        String payload = rs.getString("payload");
        String metadata = rs.getString("metadata");
        Instant occurredAt = rs.getTimestamp("occurred_at").toInstant();
        long globalSeq = rs.getLong("global_seq");
        AccountEvent event = jsonMapper.fromJson(type, payload, aggregateId, occurredAt);
        return new StoredEvent(aggregateId, sequence, eventId, type, event, payload, metadata, occurredAt, globalSeq);
    }
}
