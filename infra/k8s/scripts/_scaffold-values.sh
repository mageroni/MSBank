#!/usr/bin/env bash
set -euo pipefail
CHARTS="$(cd "$(dirname "$0")/.." && pwd)/charts"

write_chart_yaml() {
  local name="$1" desc="$2"
  cat > "$CHARTS/$name/Chart.yaml" <<EOF
apiVersion: v2
name: $name
description: $desc
type: application
version: 0.1.0
appVersion: "0.1.0"
kubeVersion: ">=1.28.0-0"
EOF
}

write_values() {
  local name="$1" port="$2" wikey="$3" extra="$4"
  cat > "$CHARTS/$name/values.yaml" <<EOF
# Default values for $name. Most globals come from the umbrella chart;
# only sub-chart-specific knobs live here.

# Globals are normally injected by the umbrella chart. The defaults below
# allow this sub-chart to be installed standalone for dev/testing.
global:
  namespace: msbank
  image:
    registry: acr.azurecr.io
    repositoryPrefix: msbank
    tag: "0.1.0"
    pullPolicy: IfNotPresent
    pullSecrets:
      - name: acr-pull-secret
  otel:
    endpoint: http://otel-collector.observability.svc.cluster.local:4317
    serviceNamespace: msbank
  workloadIdentity:
    enabled: true
    tenantId: "00000000-0000-0000-0000-000000000000"
  kafka:
    bootstrapServers: ""
    securityProtocol: SASL_SSL
    saslMechanism: PLAIN
    saslUsername: "\$ConnectionString"
  postgres:
    host: ""
    port: 5432
    user: msbank
    sslMode: require
  redis:
    host: ""
    port: 6380
    tls: true
  auth:
    issuer: "https://auth.msbank.example.com"
    audience: "msbank"
    accessTtlSeconds: 900
    refreshTtlSeconds: 2592000
  nodeSelector: {}
  pod:
    topologySpread:
      enabled: false

replicaCount: 2

# Workload identity client ID (UUID of the Azure managed identity for this svc)
workloadIdentityClientId: "$wikey"

image:
  # registry: <override of global>
  # repository: msbank/$name
  # tag: 0.1.0
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: $port
  targetPort: $port

serviceAccount:
  create: true
  name: ""
  annotations: {}

logLevel: info

probes:
  startup:
    path: /healthz
  readiness:
    path: /readyz
  liveness:
    path: /healthz

resources:
  requests:
    cpu: 100m
    memory: 256Mi
  limits:
    cpu: 500m
    memory: 512Mi

readOnlyRootFilesystem: true

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 8
  targetCPUUtilizationPercentage: 70
  targetMemoryUtilizationPercentage: 80

pdb:
  enabled: true
  minAvailable: 1

networkPolicy:
  enabled: true
  allowIngressFromIngressController: false
  extraEgress: []

podAnnotations: {}
extraEnv: {}
env: []
extraVolumes: []
extraVolumeMounts: []
$extra
EOF
}

write_chart_yaml api-gateway            "Node.js API gateway for microservice-bank."
write_chart_yaml auth-service           "Java auth service for microservice-bank."
write_chart_yaml accounts-service       "Java accounts service for microservice-bank."
write_chart_yaml transactions-service   "Go transactions service for microservice-bank."
write_chart_yaml notifications-service  "Python notifications service for microservice-bank."
write_chart_yaml web-portal             "Next.js web portal for microservice-bank."

# api-gateway: ingress-controller traffic allowed
write_values api-gateway 8080 "00000000-0000-0000-0000-000000000001" "
networkPolicy:
  enabled: true
  allowIngressFromIngressController: true
  extraEgress: []

ingress:
  enabled: true

extraEnv:
  GATEWAY_PORT: \"8080\"
  GATEWAY_RATE_LIMIT_PER_MIN: \"120\"
  GATEWAY_CORS_ORIGINS: \"https://app.msbank.example.com\"
"

write_values auth-service 8081 "00000000-0000-0000-0000-000000000002" "
extraEnv:
  AUTH_DB_NAME: \"auth\"
"

write_values accounts-service 8082 "00000000-0000-0000-0000-000000000003" "
extraEnv:
  ACCOUNTS_DB_NAME: \"accounts\"
"

write_values transactions-service 8083 "00000000-0000-0000-0000-000000000004" "
# Go service — supports read-only rootfs cleanly.
extraEnv:
  TRANSACTIONS_DB_NAME: \"transactions\"
"

write_values notifications-service 8084 "00000000-0000-0000-0000-000000000005" "
extraEnv:
  NOTIFICATIONS_DB_NAME: \"notifications\"
  SMTP_HOST: \"smtp.azurecomm.net\"
  SMTP_PORT: \"587\"
  SMTP_FROM: \"no-reply@msbank.example.com\"
"

write_values web-portal 3000 "00000000-0000-0000-0000-000000000006" "
readOnlyRootFilesystem: false  # Next.js needs writable .next/cache at runtime

networkPolicy:
  enabled: true
  allowIngressFromIngressController: true
  extraEgress: []

ingress:
  enabled: true

extraEnv:
  NEXT_PUBLIC_API_BASE_URL: \"https://msbank.example.com\"
  PORT: \"3000\"
"

echo "Wrote Chart.yaml and values.yaml for all sub-charts."
