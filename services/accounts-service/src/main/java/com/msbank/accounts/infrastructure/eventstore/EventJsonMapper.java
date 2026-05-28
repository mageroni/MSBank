package com.msbank.accounts.infrastructure.eventstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msbank.accounts.domain.AccountEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Serializes/deserializes {@link AccountEvent} variants to/from JSON.
 * Centralised so the event store and outbox both use the same on-wire shape.
 */
@Component
public class EventJsonMapper {

    private final ObjectMapper mapper;

    public EventJsonMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String typeName(AccountEvent event) {
        return event.getClass().getSimpleName();
    }

    public String toJson(AccountEvent event) {
        try {
            ObjectNode node = mapper.valueToTree(event);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("event serialize failed", e);
        }
    }

    public AccountEvent fromJson(String type, String json, UUID aggregateId, Instant occurredAt) {
        try {
            JsonNode node = mapper.readTree(json);
            return switch (type) {
                case "AccountOpened" -> new AccountEvent.AccountOpened(
                        aggregateId,
                        UUID.fromString(node.get("customerId").asText()),
                        node.get("accountType").asText(),
                        node.get("currency").asText(),
                        node.hasNonNull("nickname") ? node.get("nickname").asText() : null,
                        occurredAt);
                case "Deposited" -> new AccountEvent.Deposited(
                        aggregateId,
                        node.get("amount").asLong(),
                        node.get("currency").asText(),
                        node.hasNonNull("idempotencyKey") ? UUID.fromString(node.get("idempotencyKey").asText()) : null,
                        node.hasNonNull("description") ? node.get("description").asText() : null,
                        occurredAt);
                case "Withdrawn" -> new AccountEvent.Withdrawn(
                        aggregateId,
                        node.get("amount").asLong(),
                        node.get("currency").asText(),
                        node.hasNonNull("idempotencyKey") ? UUID.fromString(node.get("idempotencyKey").asText()) : null,
                        node.hasNonNull("description") ? node.get("description").asText() : null,
                        occurredAt);
                case "Frozen" -> new AccountEvent.Frozen(
                        aggregateId,
                        node.hasNonNull("reason") ? node.get("reason").asText() : null,
                        occurredAt);
                case "Unfrozen" -> new AccountEvent.Unfrozen(aggregateId, occurredAt);
                case "Closed" -> new AccountEvent.Closed(aggregateId, occurredAt);
                case "FundsReserved" -> new AccountEvent.FundsReserved(
                        aggregateId,
                        UUID.fromString(node.get("reservationId").asText()),
                        UUID.fromString(node.get("transactionId").asText()),
                        node.get("amount").asLong(),
                        node.get("currency").asText(),
                        occurredAt);
                case "ReservationCommitted" -> new AccountEvent.ReservationCommitted(
                        aggregateId,
                        UUID.fromString(node.get("reservationId").asText()),
                        UUID.fromString(node.get("transactionId").asText()),
                        node.get("amount").asLong(),
                        node.get("currency").asText(),
                        occurredAt);
                case "ReservationReleased" -> new AccountEvent.ReservationReleased(
                        aggregateId,
                        UUID.fromString(node.get("reservationId").asText()),
                        UUID.fromString(node.get("transactionId").asText()),
                        node.get("amount").asLong(),
                        node.get("currency").asText(),
                        occurredAt);
                default -> throw new IllegalArgumentException("unknown event type " + type);
            };
        } catch (IOException e) {
            throw new IllegalStateException("event deserialize failed", e);
        }
    }

    public ObjectMapper mapper() { return mapper; }
}
