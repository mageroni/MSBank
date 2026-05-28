package com.msbank.auth.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msbank.auth.domain.OutboxEvent;
import com.msbank.auth.infrastructure.jpa.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writes outbox rows in the same transaction as the domain change. Callers
 * must invoke this from within an open transaction; the {@code MANDATORY}
 * propagation enforces that.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repo;
    private final ObjectMapper mapper;

    public OutboxWriter(OutboxEventRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent write(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> data) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("eventId", UUID.randomUUID().toString());
            payload.put("eventType", eventType);
            payload.put("eventVersion", 1);
            payload.put("occurredAt", java.time.Instant.now().toString());
            String correlationId = MDC.get("correlationId");
            if (correlationId == null) correlationId = UUID.randomUUID().toString();
            payload.put("correlationId", correlationId);
            payload.put("source", "auth-service");
            payload.set("data", mapper.valueToTree(data));

            OutboxEvent ev = new OutboxEvent();
            ev.setAggregateType(aggregateType);
            ev.setAggregateId(aggregateId);
            ev.setEventType(eventType);
            ev.setEventVersion(1);
            ev.setCorrelationId(correlationId);
            ev.setPayload(mapper.writeValueAsString(payload));
            return repo.save(ev);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write outbox event", e);
        }
    }
}
