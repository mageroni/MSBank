# API Gateway

Edge API + BFF for the `microservice-bank` platform. Written in TypeScript on Node 20 (ESM).

## Responsibilities

- TLS-terminated edge with `helmet`, CORS, compression, JSON limit 1 MB.
- JWT (RS256) verification against the issuer's remote JWKS (cached via `jose.createRemoteJWKSet`).
- Redis-backed global + per-endpoint rate limiting.
- Redis-backed `Idempotency-Key` handling for `POST /api/v1/transfers`.
- Proxying to upstream microservices via `http-proxy-middleware`, each wrapped in an `opossum` circuit breaker.
- BFF aggregator for `GET /bff/v1/dashboard` (parallel `axios` calls with retries + breakers).
- RFC 7807 (`application/problem+json`) error responses with `correlationId` + OTel `traceId`.
- OpenTelemetry auto-instrumentation, Prometheus `/metrics`, structured `pino` logs.
- `/healthz` (liveness) and `/readyz` (Redis + auth-service reachable).

## Routes

| Method | Path                              | Auth | Notes                                                        |
| ------ | --------------------------------- | ---- | ------------------------------------------------------------ |
| GET    | `/healthz`                        | no   | Liveness.                                                    |
| GET    | `/readyz`                         | no   | Redis ping + `auth-service /healthz` (1 s timeout).          |
| GET    | `/metrics`                        | no   | Prometheus exposition.                                       |
| POST   | `/api/v1/auth/register`           | no   | Proxied to `AUTH_SERVICE_URL`.                               |
| POST   | `/api/v1/auth/login`              | no   | Proxied to auth. Tighter rate limit (10/min/IP).             |
| POST   | `/api/v1/auth/refresh`            | no   | Proxied to auth.                                             |
| GET    | `/api/v1/auth/me`                 | yes  | Proxied to auth.                                             |
| GET    | `/api/v1/accounts`                | yes  | Proxied to `ACCOUNTS_SERVICE_URL`.                           |
| POST   | `/api/v1/accounts`                | yes  | Proxied to accounts.                                         |
| GET    | `/api/v1/transfers`               | yes  | Proxied to `TRANSACTIONS_SERVICE_URL`.                       |
| POST   | `/api/v1/transfers`               | yes  | Requires `Idempotency-Key`; Redis-backed replay.             |
| GET    | `/api/v1/notifications`           | yes  | Proxied to `NOTIFICATIONS_SERVICE_URL`.                      |
| GET    | `/bff/v1/dashboard`               | yes  | Parallel fan-out + aggregation.                              |
| ANY    | `/internal/*`                     | n/a  | Always 404 (never exposed).                                  |

All upstream calls receive `x-correlation-id` (echoed back on the response) and `x-user-{id,email,roles}` (when authenticated).

## Environment

| Variable                       | Required | Default                  | Description                                       |
| ------------------------------ | -------- | ------------------------ | ------------------------------------------------- |
| `GATEWAY_PORT`                 | no       | `8080`                   | HTTP listener port.                               |
| `GATEWAY_RATE_LIMIT_PER_MIN`   | no       | `120`                    | Global limit per IP per minute.                   |
| `GATEWAY_CORS_ORIGINS`         | no       | `http://localhost:3000`  | Comma-separated allow-list.                       |
| `AUTH_SERVICE_URL`             | yes      |                          | Upstream auth-service base URL.                   |
| `ACCOUNTS_SERVICE_URL`         | yes      |                          | Upstream accounts-service base URL.               |
| `TRANSACTIONS_SERVICE_URL`     | yes      |                          | Upstream transactions-service base URL.           |
| `NOTIFICATIONS_SERVICE_URL`    | yes      |                          | Upstream notifications-service base URL.          |
| `JWT_ISSUER`                   | yes      |                          | OIDC issuer; JWKS fetched at `/.well-known/jwks.json`. |
| `JWT_AUDIENCE`                 | yes      |                          | Required `aud` claim.                             |
| `REDIS_URL`                    | yes      |                          | Used for rate limit + idempotency.                |
| `OTEL_EXPORTER_OTLP_ENDPOINT`  | no       |                          | Disables OTel when unset.                         |
| `LOG_LEVEL`                    | no       | `info`                   | pino log level.                                   |

## Run

```bash
# install
npm install

# typecheck + lint
npm run typecheck
npm run lint

# dev (tsx watch)
npm run dev

# tests
npm test

# production build
npm run build
npm start
```

### Docker

```bash
docker build -t msbank/api-gateway:dev .
docker run --rm -p 8080:8080 --env-file ../../.env msbank/api-gateway:dev
```

## Extending

- **Add a new upstream**: create `src/proxies/<name>Proxy.ts` calling `makeProxyHandler`, then mount it in `src/routes.ts`. The factory wires a circuit breaker and metrics automatically.
- **Add a BFF endpoint**: drop a router file under `src/bff/`, inject upstream URLs + `Metrics`, use `createBreaker(...)` for each axios call, mount in `routes.ts`.
- **New env var**: extend `EnvSchema` in `src/config.ts` (zod) and add it to README + `.env.example`.
- **Public path**: append to `PUBLIC_AUTH_PATHS` in `src/routes.ts` so JWT verification is skipped.

## Tests

`vitest` + `supertest`. Upstreams are mocked with `nock`, Redis with `ioredis-mock`, JWTs are signed locally and verified through a `createLocalJWKSet`.

- `tests/auth.test.ts` — bearer/audience validation, public-path bypass.
- `tests/rateLimit.test.ts` — 429 once the threshold is exceeded.
- `tests/idempotency.test.ts` — replay, 400 on missing header, 409 while in-flight.
- `tests/dashboard.test.ts` — parallel composition + breaker fallback.
