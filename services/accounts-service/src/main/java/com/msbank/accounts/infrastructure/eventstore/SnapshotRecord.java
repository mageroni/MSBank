package com.msbank.accounts.infrastructure.eventstore;

import java.time.Instant;
import java.util.UUID;

public record SnapshotRecord(UUID aggregateId, long sequence, String stateJson, Instant takenAt) {}
