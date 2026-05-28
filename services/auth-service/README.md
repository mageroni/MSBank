# auth-service

Authentication & identity microservice for the **Microservice Bank** demo.
Issues RS256 JWT access tokens + opaque refresh tokens, manages users, TOTP MFA,
and publishes user lifecycle events to Kafka via the transactional outbox.

## Stack

- Java 21, Spring Boot 3.3
- PostgreSQL + Flyway, Hibernate/JPA
- Spring Kafka (publishes `user-events`)
- Nimbus JOSE + JWT (RS256 signing, JWKS exposure)
- dev.samstevens.totp (MFA)
- Resilience4j (circuit breaker + retry on Kafka, login rate limiter)
- Micrometer + Prometheus + OpenTelemetry Spring Boot starter
- Testcontainers (Postgres, Kafka) for integration tests

## Endpoints

Implements `libs/contracts/openapi/auth.yaml`:

| Method | Path                            | Auth         | Description |
|--------|---------------------------------|--------------|-------------|
| POST   | `/api/v1/auth/register`         | public       | Register user |
| POST   | `/api/v1/auth/login`            | public + 10/min/IP | Login; returns access + refresh tokens |
| POST   | `/api/v1/auth/refresh`          | public       | Rotate refresh token |
| POST   | `/api/v1/auth/logout`           | JWT bearer   | Revoke refresh token + family |
| GET    | `/api/v1/auth/me`               | JWT bearer   | Current user profile |
| POST   | `/api/v1/auth/mfa/totp/enroll`  | JWT bearer   | Enroll TOTP (returns provisioning URI + base32 secret) |
| GET    | `/.well-known/jwks.json`        | public       | JWKS (RSA public key) |
| GET    | `/healthz`                      | public       | Liveness |
| GET    | `/readyz`                       | public       | Readiness (Postgres + Kafka) |
| GET    | `/actuator/{health,info,prometheus}` | public  | Operability |

Error responses follow **RFC 7807** (`application/problem+json`) and include
the current `traceId` from the active span.

## Environment variables

Read from the project-level `.env.example` (see repo root). The most relevant:

| Variable                       | Default                                    | Purpose |
|--------------------------------|--------------------------------------------|---------|
| `AUTH_DB_URL`                  | `jdbc:postgresql://localhost:5432/auth`    | JDBC URL |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `msbank` / `msbank_dev_only`         | DB creds |
| `KAFKA_BOOTSTRAP_SERVERS`      | `localhost:9092`                           | Broker |
| `JWT_ISSUER`                   | `https://auth.msbank.local`                | `iss` claim |
| `JWT_AUDIENCE`                 | `msbank`                                   | `aud` claim |
| `JWT_ACCESS_TTL_SECONDS`       | `900`                                      | Access TTL |
| `JWT_REFRESH_TTL_SECONDS`      | `2592000`                                  | Refresh TTL |
| `JWT_PRIVATE_KEY_PATH`         | `./keys/jwt_private.pem`                   | RSA private key (auto-generated on first boot if missing) |
| `JWT_PUBLIC_KEY_PATH`          | `./keys/jwt_public.pem`                    | RSA public key |
| `OTEL_EXPORTER_OTLP_ENDPOINT`  | `http://localhost:4317`                    | OTLP traces |

## Run locally

### Option A — `spring-boot:run` (requires Postgres + Kafka reachable)

```bash
docker run -d --name pg-auth -e POSTGRES_USER=msbank -e POSTGRES_PASSWORD=msbank_dev_only \
  -e POSTGRES_DB=auth -p 5432:5432 postgres:16-alpine
docker run -d --name redpanda -p 9092:9092 redpandadata/redpanda:latest \
  redpanda start --smp 1 --memory 1G --reserve-memory 0M --overprovisioned --node-id 0 \
  --kafka-addr 0.0.0.0:9092 --advertise-kafka-addr localhost:9092

./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Service listens on `:8081`. RSA keypair is generated at `./keys/` on first boot.

### Option B — Docker

```bash
docker build -t msbank/auth-service:dev .
docker run --rm -p 8081:8081 \
  -e AUTH_DB_URL=jdbc:postgresql://host.docker.internal:5432/auth \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -v $(pwd)/keys:/etc/msbank/keys \
  -e JWT_PRIVATE_KEY_PATH=/etc/msbank/keys/jwt_private.pem \
  -e JWT_PUBLIC_KEY_PATH=/etc/msbank/keys/jwt_public.pem \
  msbank/auth-service:dev
```

Or join the root `docker-compose.yml` of the demo.

### Smoke test

```bash
curl -s localhost:8081/healthz
curl -s -X POST localhost:8081/api/v1/auth/register \
  -H 'content-type: application/json' \
  -d '{"email":"a@b.co","password":"Str0ng-Pa55word!","firstName":"A","lastName":"B"}'
```

## Tests

```bash
./mvnw test                    # unit tests
./mvnw verify                  # adds the @SpringBootTest integration test (needs Docker)
```

`AuthFlowIntegrationTest` spins up Postgres + Kafka via Testcontainers and covers
the full register → login → refresh → me path plus outbox→Kafka delivery.

## Notable design decisions

- **Transactional outbox**: every state-changing operation writes a row to
  `outbox_events` inside the same DB transaction. A scheduled poller
  (`OutboxPoller`, fires every 500ms) selects pending rows with `FOR UPDATE
  SKIP LOCKED`, publishes to Kafka, marks them sent, and applies exponential
  backoff on failure. Resilience4j circuit breaker + retry guard the send.
- **Refresh token rotation**: opaque 256-bit base64url tokens stored as SHA-256
  hashes. Each rotation revokes the old token and links it via `replaced_by`;
  reuse of a revoked token revokes the entire family (replay detection).
- **JWT keys**: generated on first boot if absent. Persist via a host volume to
  keep tokens valid across container restarts. Public JWK is exposed at
  `/.well-known/jwks.json`.
- **MFA**: TOTP secret stored base32 in `mfa_secrets`. Enrollment marks the
  user as `mfaEnabled = true`; subsequent logins require `totpCode`.
- **Observability**: OpenTelemetry Spring Boot starter (preferred over the
  Java agent to keep the Docker image self-contained). Traces exported via
  OTLP gRPC to the collector configured by `OTEL_EXPORTER_OTLP_ENDPOINT`.
  Metrics exposed via `/actuator/prometheus`.

## Deviations from the contract

None — all endpoints, status codes, and schemas match `auth.yaml` v1.0.0.
