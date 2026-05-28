package com.msbank.accounts.infrastructure.eventstore;

import com.msbank.accounts.domain.AccountEvent;
import java.time.Instant;
import java.util.UUID;

public record StoredEvent(
        UUID aggregateId,
        long sequence,
        UUID eventId,
        String eventType,
        AccountEvent event,
        String payloadJson,
        String metadataJson,
        Instant occurredAt,
        long globalSeq
) {}
