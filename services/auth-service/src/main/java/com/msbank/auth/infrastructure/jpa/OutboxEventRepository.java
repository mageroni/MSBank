package com.msbank.auth.infrastructure.jpa;

import com.msbank.auth.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Lock a batch of pending rows using SELECT ... FOR UPDATE SKIP LOCKED, so
     * multiple replicas can poll concurrently without colliding.
     * Note: lock mode is expressed inline in SQL; @Lock is not supported on native queries.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("now") Instant now, @Param("limit") int limit);
}
