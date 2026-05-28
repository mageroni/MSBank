# Observability Stack — microservice-bank (local)

The local stack ships a full o11y pipeline so every service is traceable,
queryable and dashboarded from the moment it boots.

## Topology

```
[ services ] --OTLP gRPC:4317--> [ otel-collector ] --> [ Jaeger | Prometheus | Elasticsearch ]
                                                          ^               ^
                                                          |               |
                                       Prometheus scrape  |               |  Filebeat (Docker autodiscover)
                                       (services/metrics) |               |
                                                          |               |
                                                       [ Grafana ] <------+
                                                          ^
                                                          |
                                                       [ Kibana ]  (logs UI alternative)
```

## URLs

| Component          | URL                              |
| ------------------ | -------------------------------- |
| Web Portal         | http://localhost:3000            |
| API Gateway        | http://localhost:8080            |
| Grafana            | http://localhost:3001            |
| Prometheus         | http://localhost:9090            |
| Jaeger UI          | http://localhost:16686           |
| Kibana             | http://localhost:5601            |
| Elasticsearch      | http://localhost:9200            |
| Redpanda Console   | http://localhost:8088            |
| MailHog UI         | http://localhost:8025            |
| OTel gRPC / HTTP   | localhost:4317 / 4318            |

## Provisioned in Grafana

Datasources: Prometheus (default), Jaeger (with trace→log correlation to
Elasticsearch), Elasticsearch (`msbank-logs-*` index).

Dashboards (under `msbank` folder):

* **overview** — request rate, error rate, p95 latency per service.
* **kafka** — Redpanda consumer lag & throughput.
* **transfers** — saga state distribution (`msbank_saga_state_total`).

## Troubleshooting

* **No metrics from a service** — confirm `/metrics` (or
  `/actuator/prometheus` for Spring Boot) is reachable from inside the
  network: `docker compose exec prometheus wget -qO- http://<service>:<port>/metrics`.
* **No traces in Jaeger** — verify `OTEL_EXPORTER_OTLP_ENDPOINT` points
  at `http://otel-collector:4317` and that the collector logs show
  received spans (`docker compose logs otel-collector`).
* **No logs in Kibana/Grafana** — check Filebeat output:
  `docker compose logs filebeat`. Index pattern should be `msbank-logs-*`.
* **Elasticsearch unhealthy on low-memory hosts** — bump Docker's
  memory limit to ≥ 4 GB; ES heap is pinned at 1 GB.
* **Redpanda fails to start on Apple Silicon** — Redpanda images are
  multi-arch; if performance is poor, reduce `--memory` or pin to an
  older tag (`v23.x`).

## Config file map

```
infra/
  docker/docker-compose.yml                # full stack
  docker/postgres/<svc>/init.sql           # per-DB bootstrap (pgcrypto)
  observability/
    otel/otel-collector-config.yaml        # receivers/processors/exporters
    prometheus/prometheus.yml              # scrape configs
    grafana/provisioning/datasources/      # Prometheus, Jaeger, ES
    grafana/provisioning/dashboards/       # dashboard provider
    grafana/dashboards/                    # JSON dashboards
    filebeat/filebeat.yml                  # docker autodiscover
```
