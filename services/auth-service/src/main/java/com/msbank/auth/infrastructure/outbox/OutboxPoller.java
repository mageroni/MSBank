package com.msbank.auth.infrastructure.outbox;

import com.msbank.auth.config.AuthProperties;
import com.msbank.auth.domain.OutboxEvent;
import com.msbank.auth.infrastructure.jpa.OutboxEventRepository;
import com.msbank.auth.infrastructure.kafka.KafkaEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls the outbox every {@code auth.outbox.poll-interval-ms} (default 500ms),
 * publishes pending events to Kafka, and marks them sent. Uses SELECT FOR
 * UPDATE SKIP LOCKED so multiple replicas can run concurrently.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository repo;
    private final KafkaEventPublisher publisher;
    private final AuthProperties props;

    public OutboxPoller(OutboxEventRepository repo, KafkaEventPublisher publisher, AuthProperties props) {
        this.repo = repo;
        this.publisher = publisher;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${auth.outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        Instant now = Instant.now();
        List<OutboxEvent> batch = repo.lockPendingBatch(now, props.outbox().batchSize());
        if (batch.isEmpty()) return;

        for (OutboxEvent ev : batch) {
            try {
                publisher.publish(ev.getAggregateId().toString(), ev.getPayload());
                ev.setStatus(OutboxEvent.Status.SENT);
                ev.setSentAt(Instant.now());
                ev.setLastError(null);
            } catch (Exception e) {
                ev.setAttempts(ev.getAttempts() + 1);
                ev.setLastError(truncate(e.getMessage()));
                if (ev.getAttempts() >= props.outbox().maxAttempts()) {
                    ev.setStatus(OutboxEvent.Status.FAILED);
                    log.error("Outbox event {} failed permanently after {} attempts", ev.getId(), ev.getAttempts(), e);
                } else {
                    long backoffSec = (long) Math.pow(2, Math.min(ev.getAttempts(), 6));
                    ev.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(backoffSec)));
                    log.warn("Outbox event {} attempt {} failed: {}", ev.getId(), ev.getAttempts(), e.getMessage());
                }
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
