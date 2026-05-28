-- V1: Event store, snapshots, projections, reservations, outbox
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Append-only event store. Optimistic concurrency via UNIQUE(aggregate_id, sequence).
CREATE TABLE events (
    aggregate_id  UUID         NOT NULL,
    sequence      BIGINT       NOT NULL,
    event_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type    TEXT         NOT NULL,
    payload       JSONB        NOT NULL,
    metadata      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    global_seq    BIGSERIAL    NOT NULL,
    PRIMARY KEY (aggregate_id, sequence)
);
CREATE UNIQUE INDEX events_global_seq_uidx ON events (global_seq);
CREATE INDEX events_aggregate_id_idx ON events (aggregate_id);
CREATE INDEX events_event_type_idx ON events (event_type);

-- Snapshots taken every N events to bound replay cost.
CREATE TABLE snapshots (
    aggregate_id UUID        PRIMARY KEY,
    sequence     BIGINT      NOT NULL,
    state        JSONB       NOT NULL,
    taken_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Projection checkpoints: tracks the last global_seq each projection consumed.
CREATE TABLE projection_checkpoints (
    name           TEXT        PRIMARY KEY,
    last_global_seq BIGINT     NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Read model: current account state. Read endpoints query this table.
CREATE TABLE account_view (
    id                UUID        PRIMARY KEY,
    customer_id       UUID        NOT NULL,
    account_type      TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    balance           BIGINT      NOT NULL DEFAULT 0,
    available_balance BIGINT      NOT NULL DEFAULT 0,
    currency          TEXT        NOT NULL,
    nickname          TEXT,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX account_view_customer_idx ON account_view (customer_id);
CREATE INDEX account_view_status_idx ON account_view (status);

-- Outstanding reservations (saga holds). Used to enforce idempotency.
CREATE TABLE reservations (
    reservation_id UUID        PRIMARY KEY,
    account_id     UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    amount         BIGINT      NOT NULL,
    currency       TEXT        NOT NULL,
    status         TEXT        NOT NULL, -- HELD | COMMITTED | RELEASED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reservations_account_idx ON reservations (account_id);

-- Transactional outbox: events emitted to Kafka, written in the same TX as the event store.
CREATE TABLE outbox_events (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      TEXT        NOT NULL,
    envelope        JSONB       NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT         NOT NULL DEFAULT 0,
    last_error      TEXT
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;
