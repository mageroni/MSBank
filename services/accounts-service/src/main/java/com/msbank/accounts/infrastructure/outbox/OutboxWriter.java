package com.msbank.accounts.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msbank.accounts.domain.AccountEvent;
import com.msbank.accounts.infrastructure.eventstore.EventJsonMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes outbox rows in the same DB transaction as the event store append.
 * The envelope follows the AsyncAPI envelope schema (libs/contracts/asyncapi/events.yaml).
 */
@Component
public class OutboxWriter {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_events (event_id, aggregate_id, event_type, envelope, occurred_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            """;

    private final JdbcTemplate jdbc;
    private final EventJsonMapper jsonMapper;
    private final ObjectMapper objectMapper;
    private final String source;

    public OutboxWriter(JdbcTemplate jdbc,
                        EventJsonMapper jsonMapper,
                        ObjectMapper objectMapper,
                        @Value("${spring.application.name:accounts-service}") String source) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.objectMapper = objectMapper;
        this.source = source;
    }

    public void write(UUID eventId, AccountEvent event, String correlationId, String causationId) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("eventId", eventId.toString());
            envelope.put("eventType", jsonMapper.typeName(event));
            envelope.put("eventVersion", 1);
            envelope.put("occurredAt", event.occurredAt().toString());
            if (correlationId != null) envelope.put("correlationId", correlationId);
            if (causationId != null) envelope.put("causationId", causationId);
            envelope.put("source", source);
            envelope.set("data", buildData(event));

            jdbc.update(INSERT_SQL,
                    eventId,
                    event.aggregateId(),
                    jsonMapper.typeName(event),
                    objectMapper.writeValueAsString(envelope),
                    java.sql.Timestamp.from(event.occurredAt()));
        } catch (Exception e) {
            throw new IllegalStateException("outbox write failed", e);
        }
    }

    private ObjectNode buildData(AccountEvent event) {
        ObjectNode data = objectMapper.createObjectNode();
        switch (event) {
            case AccountEvent.AccountOpened e -> {
                data.put("accountId", e.aggregateId().toString());
                data.put("customerId", e.customerId().toString());
                data.put("accountType", e.accountType());
                data.put("currency", e.currency());
                data.put("status", "ACTIVE");
            }
            case AccountEvent.Frozen e -> {
                data.put("accountId", e.aggregateId().toString());
                data.put("status", "FROZEN");
            }
            case AccountEvent.Deposited e -> {
                data.put("accountId", e.aggregateId().toString());
                data.put("amount", e.amount());
                data.put("currency", e.currency());
                // balance is unknown at write time without re-loading state; leave 0 sentinel
                data.put("balance", 0);
                if (e.idempotencyKey() != null) data.put("idempotencyKey", e.idempotencyKey().toString());
                if (e.description() != null) data.put("description", e.description());
            }
            case AccountEvent.Withdrawn e -> {
                data.put("accountId", e.aggregateId().toString());
                data.put("amount", -e.amount());
                data.put("currency", e.currency());
                data.put("balance", 0);
                if (e.idempotencyKey() != null) data.put("idempotencyKey", e.idempotencyKey().toString());
                if (e.description() != null) data.put("description", e.description());
            }
            case AccountEvent.FundsReserved e -> reservation(data, e.aggregateId(), e.reservationId(), e.transactionId(), e.amount(), e.currency());
            case AccountEvent.ReservationCommitted e -> reservation(data, e.aggregateId(), e.reservationId(), e.transactionId(), e.amount(), e.currency());
            case AccountEvent.ReservationReleased e -> reservation(data, e.aggregateId(), e.reservationId(), e.transactionId(), e.amount(), e.currency());
            case AccountEvent.Unfrozen e -> {
                data.put("accountId", e.aggregateId().toString());
                data.put("status", "ACTIVE");
            }
            case AccountEvent.Closed e -> {
                data.put("accountId", e.aggregateId().toString());
                data.put("status", "CLOSED");
            }
        }
        return data;
    }

    private void reservation(ObjectNode data, UUID accountId, UUID reservationId, UUID transactionId, long amount, String currency) {
        data.put("accountId", accountId.toString());
        data.put("reservationId", reservationId.toString());
        data.put("transactionId", transactionId.toString());
        data.put("amount", amount);
        data.put("currency", currency);
    }
}
