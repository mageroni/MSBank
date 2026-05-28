CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS transfers (
    id                     UUID PRIMARY KEY,
    idempotency_key        UUID NOT NULL,
    source_account_id      UUID NOT NULL,
    destination_account_id UUID NOT NULL,
    amount                 BIGINT NOT NULL CHECK (amount > 0),
    currency               TEXT NOT NULL,
    reference              TEXT,
    status                 TEXT NOT NULL,
    failure_reason         TEXT,
    reservation_id         UUID,
    correlation_id         TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_transfers_source       ON transfers(source_account_id);
CREATE INDEX IF NOT EXISTS idx_transfers_destination  ON transfers(destination_account_id);
CREATE INDEX IF NOT EXISTS idx_transfers_status       ON transfers(status);
CREATE INDEX IF NOT EXISTS idx_transfers_created_at   ON transfers(created_at);

CREATE TABLE IF NOT EXISTS saga_steps (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id  UUID NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    step         TEXT NOT NULL,
    status       TEXT NOT NULL,
    details      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_saga_steps_transfer ON saga_steps(transfer_id, occurred_at);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key          UUID PRIMARY KEY,
    transfer_id  UUID NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id    UUID NOT NULL,
    topic           TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    envelope        JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;
