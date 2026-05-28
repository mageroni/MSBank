package com.msbank.accounts.infrastructure.outbox;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox poller: reads unpublished rows, publishes envelopes to Kafka, marks them published.
 * Resilience4j circuit breaker + retry wraps the publish call.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxPublisher(JdbcTemplate jdbc,
                           KafkaTemplate<String, String> kafka,
                           @Value("${accounts.kafka.topic:account-events}") String topic,
                           @Value("${accounts.outbox.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${accounts.outbox.poll-interval-ms:500}")
    public void poll() {
        try {
            int processed = publishBatch();
            if (processed > 0) log.debug("outbox published count={}", processed);
        } catch (Exception e) {
            log.warn("outbox poll failed: {}", e.getMessage());
        }
    }

    @Transactional
    public int publishBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_id, aggregate_id, event_type, envelope
                FROM outbox_events WHERE published_at IS NULL
                ORDER BY id LIMIT ?
                """, batchSize);
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String key = row.get("aggregate_id").toString();
            String payload = row.get("envelope").toString();
            try {
                publishOne(key, payload);
                jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
            } catch (Exception e) {
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        e.getMessage(), id);
                log.warn("outbox publish failed id={}: {}", id, e.getMessage());
            }
        }
        return rows.size();
    }

    @CircuitBreaker(name = "outboxPublisher")
    @Retry(name = "outboxPublisher")
    public void publishOne(String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get();
    }
}
