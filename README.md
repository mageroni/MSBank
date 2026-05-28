# 🏦 Microservice Bank

A reference internet-banking demo built as a **polyglot microservices** platform.
The goal is not just "running code", but to illustrate the architectural
patterns that real banks use: **Event Sourcing + CQRS**, **Saga orchestration**,
**Transactional Outbox**, **Circuit Breakers**, and a full **observability**
stack — wired together end-to-end and runnable with one command.

> Demo only. Not production. No real money moves anywhere.

---

## Architecture at a glance

```
                                    ┌────────────────────────┐
                                    │   Next.js Web Portal   │
                                    │      (web-portal)      │
                                    └───────────┬────────────┘
                                                │ HTTPS
                                    ┌───────────▼────────────┐
                                    │   API Gateway / BFF    │  Node.js + TS
                                    │      (api-gateway)     │  JWT, rate-limit,
                                    └─┬───┬─────┬──────┬─────┘  idempotency,
                                      │   │     │      │        circuit breakers
              ┌───────────────────────┘   │     │      └────────────────────┐
              │                           │     │                           │
   ┌──────────▼──────────┐  ┌─────────────▼┐ ┌──▼──────────────┐  ┌─────────▼─────────┐
   │     auth-service    │  │  accounts-   │ │  transactions-  │  │   notifications-  │
   │   Java / Spring     │  │  service     │ │  service        │  │   service         │
   │   JWT(RS256)+MFA    │  │  ES + CQRS   │ │  Saga (Go)      │  │   FastAPI         │
   └──────────┬──────────┘  └──────┬───────┘ └────────┬────────┘  └─────────┬─────────┘
              │                    │                  │                     │
              └────── outbox ──────┼─── outbox ───────┼───── outbox ────────┘
                                   │                  │
                          ┌────────▼──────────────────▼─────────┐
                          │   Kafka  (Redpanda / Event Hubs)    │
                          │  user-events · account-events ·     │
                          │  transaction-events · notif-events  │
                          └─────────────────────────────────────┘
                                          │
                  ┌───────────────────────┼───────────────────────┐
            ┌─────▼─────┐           ┌─────▼─────┐           ┌─────▼─────┐
            │ Postgres  │  …×4      │   Redis   │           │  MailHog  │
            └───────────┘           └───────────┘           └───────────┘

  Observability:  OTel Collector → Jaeger (traces)
                                 → Prometheus + Grafana (metrics)
                                 → Elasticsearch + Kibana (logs)
```

### Services

| # | Service                  | Language / Framework | Port | Pattern showcase                  |
|---|--------------------------|----------------------|------|-----------------------------------|
| 1 | `api-gateway`            | Node 20 + TypeScript | 8080 | BFF, circuit breaker, idempotency |
| 2 | `auth-service`           | Java 21 + Spring Boot| 8081 | JWT RS256, JWKS, TOTP MFA, outbox |
| 3 | `accounts-service`       | Java 21 + Spring Boot| 8082 | **Event Sourcing + CQRS**, outbox |
| 4 | `transactions-service`   | Go 1.22 + chi        | 8083 | **Saga orchestration** + compensations |
| 5 | `notifications-service`  | Python 3.12 + FastAPI| 8084 | Kafka consumer, retry + DLQ       |
| 6 | `web-portal`             | Next.js 14 + TS      | 3000 | Customer-facing portal            |

### Cross-cutting

- **Contracts** — OpenAPI 3.1 (`libs/contracts/openapi/`) and AsyncAPI 2.6 (`libs/contracts/asyncapi/`).
- **Local infra** — `docker compose` brings up everything (services + 4 Postgres + Redpanda + Redis + MailHog + the full observability stack). See `infra/docker/`.
- **Kubernetes** — Helm charts per service + an umbrella chart for AKS in `infra/k8s/charts/`.
- **Cloud (Azure)** — Terraform skeleton in `infra/terraform/` (AKS, ACR, Azure DB for PostgreSQL, Event Hubs (Kafka), Cache for Redis, Key Vault, App Gateway, Log Analytics + App Insights).
- **CI/CD** — GitHub Actions in `.github/workflows/` (per-service build/test/lint, container build + Trivy scan + GHCR publish, Helm deploy to AKS).

---

## Industry patterns demonstrated

| Pattern                            | Where to look                                              |
|------------------------------------|------------------------------------------------------------|
| **Event Sourcing + CQRS**          | `services/accounts-service/src/main/java/com/msbank/accounts/domain/AccountAggregate.java` + `infrastructure/eventstore/` + `infrastructure/projection/` |
| **Saga (orchestration)** + compensations | `services/transactions-service/internal/saga/` |
| **Transactional Outbox**           | `auth-service/infrastructure/outbox/`, `accounts-service/infrastructure/outbox/`, `transactions-service/internal/events/` |
| **Circuit Breaker** + retry        | Resilience4j (Java), `opossum` (Node), `gobreaker` (Go)    |
| **Idempotency keys**               | Gateway middleware + per-service stores (Redis-backed)     |
| **RFC 7807 errors**                | All services return `application/problem+json`             |
| **OpenAPI + AsyncAPI contracts**   | `libs/contracts/`                                          |
| **OpenTelemetry**                  | OTLP from every service → collector → Jaeger/Prom/ELK      |
| **12-factor config**               | `.env.example` + `${VAR:default}` placeholders             |
| **Defense in depth**               | JWT + per-service authZ, internal token for `/internal/*`, NetworkPolicies in K8s, Key Vault + CSI in AKS |

---

## Quickstart (local)

### Prerequisites

- Docker 24+ (with Compose v2.20+)
- `make`, `bash`, `curl`, `jq`
- ~6 GB free RAM (the observability stack is the heavy part)

### Run the whole stack

```bash
cp .env.example .env          # one-time
make up                       # builds + starts everything
make ps                       # status
```

It takes ~2 minutes the first time (image builds). When everything is healthy:

| URL                                | What it is              |
|------------------------------------|-------------------------|
| http://localhost:3000              | Web portal              |
| http://localhost:8080              | API Gateway             |
| http://localhost:8088              | Redpanda Console        |
| http://localhost:8025              | MailHog UI (emails)     |
| http://localhost:9090              | Prometheus              |
| http://localhost:3001              | Grafana                 |
| http://localhost:16686             | Jaeger UI               |
| http://localhost:5601              | Kibana                  |

### Exercise it

```bash
make smoke         # runs scripts/smoke.sh end-to-end against the gateway
```

The smoke test registers a user → logs in → opens two accounts → deposits
funds → transfers between them → polls the saga to `COMPLETED` → checks
balances → verifies notifications were produced → verifies the
transfer idempotency key replays the same result.

### Or use the portal

1. Open http://localhost:3000
2. Register a new user (password ≥ 12 chars, mixed case, digit, symbol).
3. Open a CHECKING and a SAVINGS account.
4. Deposit funds (devs only: there's a deposit form on the account page).
5. Run a transfer through the wizard and watch it progress.
6. Visit MailHog at http://localhost:8025 to see the notification email.

### Tear down

```bash
make clean         # stops everything and removes named volumes
```

---

## Repository layout

```
microservice-bank/
├── README.md                      ← you are here
├── Makefile
├── docker-compose.yml             ← root include of infra/docker/docker-compose.yml
├── .env.example
├── .github/workflows/             ← CI, image build, deploy
├── libs/contracts/                ← OpenAPI + AsyncAPI specs
├── services/
│   ├── api-gateway/               ← Node.js BFF
│   ├── auth-service/              ← Spring Boot JWT issuer
│   ├── accounts-service/          ← Spring Boot ES+CQRS
│   ├── transactions-service/      ← Go saga orchestrator
│   └── notifications-service/     ← FastAPI consumer
├── web-portal/                    ← Next.js 14
├── scripts/
│   └── smoke.sh
└── infra/
    ├── docker/                    ← Compose + per-service postgres init
    ├── observability/             ← OTel, Prometheus, Grafana, Filebeat
    ├── k8s/charts/                ← Helm charts (AKS)
    └── terraform/                 ← Azure modules (AKS, ACR, PG, EventHubs, KV, …)
```

Each service folder has its own README with deeper detail.

---

## Tests

| Service                | Command                                                        |
|------------------------|----------------------------------------------------------------|
| auth-service           | `cd services/auth-service && ./mvnw test`                      |
| accounts-service       | `cd services/accounts-service && ./mvnw test`                  |
| transactions-service   | `cd services/transactions-service && go test ./...`            |
| notifications-service  | `cd services/notifications-service && pytest -q`               |
| api-gateway            | `cd services/api-gateway && npm test`                          |
| web-portal             | `cd web-portal && npm test`                                    |
| End-to-end             | `make smoke` (stack must be up)                                |

The integration tests for Java and Go services use **Testcontainers** and
require Docker. Python integration tests are gated by `RUN_INTEGRATION=1`.

---

## From local Kafka to Azure Event Hubs

The single switch that moves the platform from a laptop to Azure is the
Kafka endpoint. Every service reads:

```
KAFKA_BOOTSTRAP_SERVERS
KAFKA_SECURITY_PROTOCOL
KAFKA_SASL_MECHANISM
KAFKA_SASL_USERNAME
KAFKA_SASL_PASSWORD
```

`.env.example` shows the local values (Redpanda, PLAINTEXT) and the
Event Hubs equivalents (SASL_SSL + `$ConnectionString`). The Helm umbrella
chart's `values-aks.yaml` wires the same vars to a SecretProviderClass that
syncs the connection string from Azure Key Vault into a Kubernetes Secret.

---

## Deploying to Azure

1. `cd infra/terraform/environments/dev && terraform init && terraform apply`
   provisions the Resource Group, VNet, AKS, ACR, Azure DB for PostgreSQL,
   Event Hubs (Kafka), Redis, Key Vault, App Gateway, and Log Analytics +
   App Insights.
2. The CI workflow (`.github/workflows/images.yml`) publishes images to GHCR;
   mirror them to ACR or change the registry references in the umbrella chart.
3. `gh workflow run deploy.yml -f environment=dev -f image_tag=<tag>` deploys
   via Helm using the AKS overlay.

See `infra/terraform/README.md` and `infra/k8s/README.md` for the full
procedure, prerequisites, and teardown notes.

---

## License

Demo code, MIT.
