package com.msbank.accounts.infrastructure.projection;

import com.msbank.accounts.domain.AccountEvent;
import com.msbank.accounts.infrastructure.eventstore.EventStore;
import com.msbank.accounts.infrastructure.eventstore.StoredEvent;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side projection runner. Polls the event store for new events and updates
 * the {@code account_view} table. Offset persisted in {@code projection_checkpoints}.
 *
 * <p>Read endpoints query {@code account_view} — never the event store directly.
 */
@Component
public class AccountViewProjection {

    private static final Logger log = LoggerFactory.getLogger(AccountViewProjection.class);
    private static final String NAME = "account_view";

    private final EventStore eventStore;
    private final JdbcTemplate jdbc;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AccountViewProjection(EventStore eventStore, JdbcTemplate jdbc,
                                 @Value("${accounts.projection.batch-size:200}") int batchSize) {
        this.eventStore = eventStore;
        this.jdbc = jdbc;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${accounts.projection.poll-interval-ms:250}")
    public void runScheduled() {
        if (running.compareAndSet(false, true)) {
            try { runOnce(); } finally { running.set(false); }
        }
    }

    /** Synchronously process one batch. Returns count processed. */
    @Transactional
    public int runOnce() {
        long checkpoint = currentCheckpoint();
        List<StoredEvent> batch = eventStore.readSince(checkpoint, batchSize);
        if (batch.isEmpty()) return 0;
        for (StoredEvent se : batch) {
            try {
                applyToView(se);
            } catch (Exception e) {
                log.error("projection failed event={} seq={}", se.eventType(), se.globalSeq(), e);
                throw e;
            }
        }
        long newCheckpoint = batch.get(batch.size() - 1).globalSeq();
        updateCheckpoint(newCheckpoint);
        return batch.size();
    }

    private long currentCheckpoint() {
        Long v = jdbc.query("SELECT last_global_seq FROM projection_checkpoints WHERE name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, NAME);
        if (v == null) {
            jdbc.update("INSERT INTO projection_checkpoints (name, last_global_seq) VALUES (?, 0) ON CONFLICT DO NOTHING", NAME);
            return 0L;
        }
        return v;
    }

    private void updateCheckpoint(long v) {
        jdbc.update("UPDATE projection_checkpoints SET last_global_seq = ?, updated_at = now() WHERE name = ?", v, NAME);
    }

    private void applyToView(StoredEvent se) {
        AccountEvent event = se.event();
        Instant now = se.occurredAt();
        switch (event) {
            case AccountEvent.AccountOpened e -> jdbc.update("""
                    INSERT INTO account_view (id, customer_id, account_type, status, balance, available_balance,
                                              currency, nickname, version, created_at, updated_at)
                    VALUES (?, ?, ?, 'ACTIVE', 0, 0, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, e.aggregateId(), e.customerId(), e.accountType(), e.currency(),
                    e.nickname(), se.sequence(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            case AccountEvent.Deposited e -> jdbc.update("""
                    UPDATE account_view SET balance = balance + ?, available_balance = available_balance + ?,
                                            version = ?, updated_at = ?
                    WHERE id = ?
                    """, e.amount(), e.amount(), se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.Withdrawn e -> jdbc.update("""
                    UPDATE account_view SET balance = balance - ?, available_balance = available_balance - ?,
                                            version = ?, updated_at = ?
                    WHERE id = ?
                    """, e.amount(), e.amount(), se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.Frozen e -> jdbc.update("""
                    UPDATE account_view SET status = 'FROZEN', version = ?, updated_at = ? WHERE id = ?
                    """, se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.Unfrozen e -> jdbc.update("""
                    UPDATE account_view SET status = 'ACTIVE', version = ?, updated_at = ? WHERE id = ?
                    """, se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.Closed e -> jdbc.update("""
                    UPDATE account_view SET status = 'CLOSED', version = ?, updated_at = ? WHERE id = ?
                    """, se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.FundsReserved e -> jdbc.update("""
                    UPDATE account_view SET available_balance = available_balance - ?,
                                            version = ?, updated_at = ? WHERE id = ?
                    """, e.amount(), se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.ReservationCommitted e -> jdbc.update("""
                    UPDATE account_view SET balance = balance - ?, version = ?, updated_at = ? WHERE id = ?
                    """, e.amount(), se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
            case AccountEvent.ReservationReleased e -> jdbc.update("""
                    UPDATE account_view SET available_balance = available_balance + ?,
                                            version = ?, updated_at = ? WHERE id = ?
                    """, e.amount(), se.sequence(), java.sql.Timestamp.from(now), e.aggregateId());
        }
    }
}
