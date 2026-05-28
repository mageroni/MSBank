# Notifications Service

Production-style async Python microservice for Microservice Bank. It consumes
domain events from Kafka and dispatches templated notifications over email,
SMS and webhooks.

## Architecture

```
                      ┌──────────────────────────────┐
 user-events ──▶      │                              │
 account-events ──▶   │   Kafka consumer (aiokafka)  │
 transaction-events ─▶│                              │
                      └─────────────┬────────────────┘
                                    │
                                    ▼
                          ┌─────────────────────┐
                          │  Handlers (mapping) │
                          └────────┬────────────┘
                                   │ persist PENDING row
                                   ▼
                          ┌─────────────────────┐
                          │ NotificationRepo /  │
                          │ Postgres (asyncpg)  │
                          └────────┬────────────┘
                                   │ DispatchRequest
                                   ▼
                          ┌─────────────────────┐
                          │ Dispatcher          │  retry x5 (0.5,1,2,4,8s + jitter)
                          │ (email/sms/webhook) │
                          └────────┬────────────┘
                                   │ NotificationSent / NotificationFailed
                                   ▼
                            notification-events ──▶ Kafka
```

## Event-to-template mapping

| Event             | Channel(s)    | Template                          |
| ----------------- | ------------- | --------------------------------- |
| `UserRegistered`  | EMAIL         | `email/welcome.html`              |
| `AccountOpened`   | EMAIL         | `email/account_opened.html`       |
| `TransferCompleted` | EMAIL + SMS | `email/transfer_completed.html` + `sms/transfer_completed.txt` |
| `TransferFailed`  | EMAIL         | `email/transfer_failed.html`      |

All Jinja2 templates extend `templates/email/_base.html` for the brand header.

## Delivery flow & guarantees

* **Idempotency** — `notifications.source_event_id` has a unique index and is
  populated from the inbound `eventId`. A duplicate event becomes a no-op.
* **Offset commits** happen only *after* the row is persisted.
* **Retry** — up to 5 attempts with exponential backoff (`0.5, 1, 2, 4, 8s`)
  with full jitter. On the final failure the notification is marked
  `DEAD_LETTERED`, the `notifications_dead_letter_total` Prometheus counter is
  incremented, and a `NotificationFailed` event is published to
  `notification-events`.
* **Auth** — REST endpoints require an RS256 JWT verified against
  `${JWT_ISSUER}/.well-known/jwks.json` (cached in-memory). `/test` requires
  the `ADMIN` role.
* **Observability** — JSON structlog with trace/span IDs, OTel for FastAPI /
  asyncpg / aiokafka, Prometheus at `/metrics`.

## Endpoints

| Method | Path                          | Auth        |
| ------ | ----------------------------- | ----------- |
| GET    | `/healthz`                    | none        |
| GET    | `/readyz`                     | none        |
| GET    | `/metrics`                    | none        |
| GET    | `/api/v1/notifications`       | bearer JWT  |
| GET    | `/api/v1/notifications/{id}`  | bearer JWT  |
| POST   | `/api/v1/notifications/test`  | bearer JWT, `ADMIN` |

## Environment variables

| Variable | Purpose |
| -------- | ------- |
| `NOTIFICATIONS_DB_URL` | asyncpg DSN to Postgres |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka/Redpanda brokers |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_FROM` | Outbound SMTP (MailHog locally) |
| `JWT_ISSUER` / `JWT_AUDIENCE` | RS256 issuer + audience |
| `JWT_PUBLIC_KEY_PEM` | (optional) static PEM for tests/local |
| `REDIS_URL` | Reserved for future rate-limit/state work |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP/gRPC collector endpoint |
| `WEBHOOK_SIGNING_SECRET` | HMAC secret for outbound webhooks |

See repo root `.env.example` for defaults.

## Run locally

```bash
# Install deps (Python 3.12)
uv pip install -r requirements.txt
# or:  pip install -e '.[dev]'

# Apply migrations
alembic upgrade head

# Run the API + consumer
uvicorn app.main:app --host 0.0.0.0 --port 8084 --loop uvloop
```

## Docker

```bash
docker build -t msbank/notifications-service:dev .
docker run --rm -p 8084:8084 --env-file ../../.env msbank/notifications-service:dev
```

## Tests

```bash
pip install -e '.[dev]'

# Unit tests
pytest -q

# Integration (requires Docker for testcontainers + Postgres + Redpanda)
RUN_INTEGRATION=1 pytest -q tests/test_integration.py
```

## Lint / type-check

```bash
ruff check .
mypy --strict app
```

## Notes / deviations

* Webhook receiver email/phone fields on transfer events are taken straight
  from the envelope `data`. In production a join against the auth-service /
  customer profile service would resolve recipients.
* The SMS sender is a stub that records to logs only; replace with Twilio or
  similar.
* The integration test is opt-in via `RUN_INTEGRATION=1` to keep CI fast.
