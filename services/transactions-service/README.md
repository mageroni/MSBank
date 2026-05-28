# transactions-service

Cross-account money-movement microservice for **Microservice Bank**. Implements
a **Saga orchestrator** for transfers with persistent state, compensations,
and resumable recovery on restart.

## Saga flow

```
                ┌────────────┐  ok   ┌────────────┐  ok   ┌────────────────┐  ok   ┌────────────────┐
   create ───▶  │ RESERVE_   │ ────▶ │ DEBIT_     │ ────▶ │ CREDIT_        │ ────▶ │ EMIT_          │ ──▶ COMPLETED
                │ SOURCE     │       │ SOURCE     │       │ DESTINATION    │       │ NOTIFICATION   │
                └─────┬──────┘       └─────┬──────┘       └────────┬───────┘       └────────────────┘
                  fail│            fail    │              fail     │
                      ▼                    ▼                       ▼
                 FAILED            RELEASE_RESERVATION       REVERSE_DEBIT
                                          │                       │
                                          ▼                       ▼
                                    COMPENSATED              COMPENSATED
```

States the `transfers` row walks through:
`PENDING → RESERVED → DEBITED → CREDITED → COMPLETED`,
or `FAILED`, or `COMPENSATING → COMPENSATED`.

Each side-effecting step is wrapped in a **circuit breaker** (`sony/gobreaker`)
plus **exponential backoff retry** (`cenkalti/backoff`). Idempotency keys for
the accounts-service are deterministic: `${transferId}:${stepName}`. The full
saga log is persisted in `saga_steps` and exposed via
`GET /api/v1/transfers/{id}/saga` for audit.

### Resumability

On boot a recovery goroutine sweeps transfers in non-terminal states
(`PENDING/RESERVED/DEBITED/CREDITED/COMPENSATING`) and re-invokes the
orchestrator. Because each step is idempotent and persisted, the saga safely
picks up at the last successful step.

### Eventing

Domain events use the project's standard envelope and flow via a
**transactional outbox**: orchestrator writes `outbox_events` in the same
transaction as the state change; a background publisher drains the outbox to
Kafka. Events produced:

| Event                | When                                  |
|----------------------|---------------------------------------|
| `TransferInitiated`  | When `POST /api/v1/transfers` accepts |
| `TransferCompleted`  | After successful `CREDIT_DESTINATION` |
| `TransferFailed`     | After unrecoverable failure           |
| `TransferCompensated`| After compensation finishes           |

The service also consumes `account-events` (logged today, hookable for richer
async coordination).

## Endpoints

| Method | Path                                | Auth   | Notes                                  |
|--------|-------------------------------------|--------|----------------------------------------|
| POST   | `/api/v1/transfers`                 | Bearer | `Idempotency-Key` header required, 202 |
| GET    | `/api/v1/transfers`                 | Bearer | filter by `accountId/status/from/to`   |
| GET    | `/api/v1/transfers/{id}`            | Bearer |                                        |
| GET    | `/api/v1/transfers/{id}/saga`       | Bearer | audit log of saga steps                |
| GET    | `/healthz`                          | none   | liveness                               |
| GET    | `/readyz`                           | none   | DB ping + Kafka init flag              |
| GET    | `/metrics`                          | none   | Prometheus                             |

Errors are RFC 7807 `application/problem+json`.

## Environment variables

| Var                              | Purpose                                              |
|----------------------------------|------------------------------------------------------|
| `TRANSACTIONS_DB_URL` (required) | Postgres DSN, e.g. `postgres://…?sslmode=disable`    |
| `KAFKA_BOOTSTRAP_SERVERS`        | comma-separated brokers; omit to disable publishing  |
| `REDIS_URL`                      | for saga step locks; omit for in-process only        |
| `ACCOUNTS_SERVICE_URL`           | base URL of accounts-service                         |
| `INTERNAL_TOKEN`                 | sent as `X-Internal-Token`                           |
| `JWT_ISSUER`, `JWT_AUDIENCE`     | JWT verification via JWKS; omit for dev (no auth)    |
| `OTEL_EXPORTER_OTLP_ENDPOINT`    | OTLP endpoint (reserved for future tracer wiring)    |
| `HTTP_PORT`                      | default `8083`                                       |
| `LOG_LEVEL`                      | `debug|info|warn|error` (default `info`)             |
| `SAGA_RECOVERY_INTERVAL`         | default `30s`                                        |
| `OUTBOX_PUBLISH_INTERVAL`        | default `2s`                                         |

## Run locally

```bash
# 1. From repo root, bring up infra (postgres, redpanda, redis):
docker compose -f infra/docker-compose.yml up -d postgres-transactions redpanda redis

# 2. Run the service
export TRANSACTIONS_DB_URL="postgres://msbank:msbank_dev_only@localhost:5432/transactions?sslmode=disable"
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export REDIS_URL=redis://localhost:6379/0
export ACCOUNTS_SERVICE_URL=http://localhost:8082
go run ./cmd/server
```

## Tests

```bash
go test ./...            # unit
go test ./... -tags=integration   # integration (requires Docker)
```

The integration tests use `testcontainers-go` to spin up Postgres + Redpanda
and an `httptest` stub for accounts-service. They are guarded by
`testing.Short()` so `go test -short` skips them.

## Build container

```bash
docker build -t msbank/transactions-service .
```

Multi-stage build: `golang:1.22-alpine` → `gcr.io/distroless/static:nonroot`.

## Layout

```
cmd/server/main.go         entrypoint, wiring, migrations, graceful shutdown
internal/api               HTTP handlers, middleware, DTOs
internal/saga              orchestrator + recovery loop
internal/ledger            Postgres persistence (pgx)
internal/accounts          accounts-service HTTP client (CB + retry)
internal/events            envelope, Kafka producer/consumer, outbox publisher
internal/auth              JWT/JWKS middleware
internal/idem              Redis-backed advisory locks
internal/config            env loading
internal/observability     slog, Prometheus metrics
migrations/                golang-migrate up/down SQL
```
