# accounts-service

Hand-rolled **Event Sourcing + CQRS** bank-accounts service. Java 21, Spring Boot 3.3.

> This service deliberately does **not** use a framework like Axon. ES/CQRS is implemented
> from primitives so the moving parts are visible and inspectable.

---

## Why ES + CQRS for accounts?

Account balances are the canonical example where *what happened* is more important than
*current state*. Storing every domain event (open, deposit, withdraw, reserve, commit,
release, freeze) gives us:

- **Auditability** — every balance change is a row in `events` with a `correlationId`.
- **Time travel** — replay events up to any sequence to recover historical state.
- **Read flexibility** — multiple projections (the `account_view` here, plus future
  customer-360 / fraud / statements projections) can derive different shapes from the
  same authoritative stream.
- **Sagas / reservations** — two-phase holds via `FundsReserved` → `ReservationCommitted`
  or `ReservationReleased`, naturally idempotent on `reservationId`.

CQRS is the consequence: writes go through the aggregate; reads go to projection tables
optimized for query patterns. The split is enforced by the package layout
(`api`, `application`, `domain`, `infrastructure`).

---

## Event types (canonical names)

| Event | Effect on state |
|---|---|
| `AccountOpened` | Creates the aggregate (`balance = available = 0`, status `ACTIVE`). |
| `Deposited` | `balance += amount`, `available += amount`. |
| `Withdrawn` | `balance -= amount`, `available -= amount`. |
| `Frozen` | status → `FROZEN`. All money ops rejected. |
| `Unfrozen` | status → `ACTIVE`. |
| `Closed` | status → `CLOSED`. Terminal. |
| `FundsReserved` | `available -= amount`. `balance` unchanged. Held until commit/release. |
| `ReservationCommitted` | `balance -= amount`. Hold consumed. |
| `ReservationReleased` | `available += amount`. Hold cancelled. |

All amounts are **integer minor units** (`long`). No floating point.

---

## Architecture

```
HTTP / JWT
    │
    ▼
 AccountsController  ─── command ──▶  AccountCommandService
        │                                   │
        │ (read side)                       │  load → handle(cmd) → events
        ▼                                   ▼
 AccountQueryService              AccountRepository ─▶ EventStore (events)
        │                                              + Snapshot store
        ▼                                              + OutboxWriter
   account_view                                 (single transaction)
        ▲                                              │
        │                                              ▼
 AccountViewProjection ◀── reads via global_seq ── events
        │                                              │
        │                                              ▼
        └─ updates projection ◀──────────  OutboxPublisher ─▶ Kafka topic 'account-events'
```

- **Aggregate**: `domain.AccountAggregate`. State is mutated **only** by `apply(Event)`.
  Command handlers return events; they never mutate. `rehydrate(events)` replays an
  aggregate from history — the unit tests verify this directly.
- **Event store**: `events` table, `PRIMARY KEY (aggregate_id, sequence)`. Concurrent
  writes that pick the same next sequence hit the unique constraint and the service
  returns **HTTP 409 Conflict**.
- **Snapshots**: every N events (`accounts.snapshot.interval`, default 50) the current
  state JSON is upserted into `snapshots`. Loads read the snapshot then replay only the
  tail.
- **Projection**: `AccountViewProjection` runs every 250 ms (`@Scheduled`), pulls new
  events via `events.global_seq` (using the offset in `projection_checkpoints`) and
  upserts `account_view`. The query endpoints read **only** from `account_view`.
- **Reservations**: side table `reservations` enables fast idempotency lookup; the
  aggregate still owns the rules.
- **Outbox**: each event store insert also inserts a row into `outbox_events` in the
  same DB transaction; `OutboxPublisher` polls + publishes to Kafka with a
  Resilience4j circuit breaker + retry.
- **Internal endpoints**: `/internal/**` is gated by a static `X-Internal-Token` header
  (not JWT). Resilience4j **bulkhead** caps concurrent calls.

---

## Inspecting the event store

```sql
-- All events for an account, in order:
SELECT sequence, event_type, payload, occurred_at
FROM events WHERE aggregate_id = '…uuid…' ORDER BY sequence;

-- Latest snapshot:
SELECT sequence, taken_at, jsonb_pretty(state) FROM snapshots WHERE aggregate_id = '…uuid…';

-- Projection lag:
SELECT name, last_global_seq, updated_at FROM projection_checkpoints;
SELECT max(global_seq) FROM events;       -- compare against last_global_seq

-- Outbox lag:
SELECT count(*) FROM outbox_events WHERE published_at IS NULL;
```

The HTTP audit endpoint `GET /api/v1/accounts/{id}/events` returns the same stream as
the SQL above (subject to ownership check).

---

## Environment

| Variable | Purpose |
|---|---|
| `ACCOUNTS_DB_URL` | JDBC URL for Postgres (one DB per service). |
| `KAFKA_BOOTSTRAP_SERVERS` | Redpanda / Event Hubs bootstrap servers. |
| `JWT_ISSUER` | OAuth2 issuer; JWKS resolved at `${JWT_ISSUER}/.well-known/jwks.json`. |
| `JWT_AUDIENCE` | Expected `aud` claim. |
| `INTERNAL_TOKEN` | Static token for `/internal/**` callers. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP collector. |

---

## Run

### Local (requires Postgres + Kafka)

```bash
./mvnw spring-boot:run
```

### Build container

```bash
docker build -t accounts-service:dev .
docker run --rm -p 8082:8082 \
  -e ACCOUNTS_DB_URL=jdbc:postgresql://host.docker.internal:5432/accounts \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e JWT_ISSUER=http://host.docker.internal:8081 \
  -e JWT_AUDIENCE=msbank \
  -e INTERNAL_TOKEN=dev-internal-token \
  accounts-service:dev
```

### Tests

- Unit tests: `./mvnw test -Dtest=AccountAggregateTest`
- Integration (Testcontainers — needs Docker): `./mvnw test`

The integration test spins up Postgres + Kafka, opens an account via HTTP, deposits,
asserts the projection catches up, and consumes the outbox-published Kafka envelopes.

---

## Endpoints

See `libs/contracts/openapi/accounts.yaml`. Summary:

- `POST /api/v1/accounts` — open account.
- `GET /api/v1/accounts[?status=ACTIVE|FROZEN|CLOSED]` — list for caller.
- `GET /api/v1/accounts/{id}` — read projection.
- `GET /api/v1/accounts/{id}/events` — full event history (audit).
- `POST /api/v1/accounts/{id}/deposits` — deposit (idempotent by `idempotencyKey`).
- `POST /api/v1/accounts/{id}/withdrawals` — withdraw.
- `POST /api/v1/accounts/{id}/freeze` — freeze.
- `POST /internal/api/v1/accounts/{id}/reservations` — reserve funds (saga).
- `POST /internal/api/v1/accounts/{id}/reservations/{reservationId}/commit`
- `POST /internal/api/v1/accounts/{id}/reservations/{reservationId}/release`

Errors use **RFC 7807** (`application/problem+json`).

---

## Observability

- `/actuator/health`, `/actuator/prometheus` are exposed.
- Micrometer OTel bridge → OTLP exporter (set `OTEL_EXPORTER_OTLP_ENDPOINT`).
- Logs are JSON via Logstash encoder (`logback-spring.xml`).
