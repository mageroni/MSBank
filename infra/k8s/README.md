# microservice-bank — Helm charts

Umbrella + per-service Helm charts targeting **Azure Kubernetes Service (AKS)**
on Kubernetes 1.28+, with values overlays for local kind/minikube clusters.

```
infra/k8s/
├── README.md                       # this file
├── scripts/
│   ├── lint.sh                     # helm lint + helm template across all charts
│   └── _scaffold-*.sh              # internal scaffolding helpers (used at author time)
└── charts/
    ├── microservice-bank/          # umbrella chart
    │   ├── Chart.yaml
    │   ├── values.yaml             # global defaults (Azure/AKS oriented)
    │   ├── values-aks.yaml         # AKS overlay
    │   ├── values-local.yaml       # kind/minikube overlay (enables bundled PG/Kafka/Redis)
    │   └── templates/
    │       ├── _helpers.tpl
    │       ├── namespace.yaml
    │       ├── podsecurity.yaml
    │       ├── secretproviderclass.yaml
    │       └── networkpolicy-default-deny.yaml
    ├── api-gateway/                # Node, port 8080  (+ Ingress for gateway + web-portal)
    ├── auth-service/               # Java, port 8081
    ├── accounts-service/           # Java, port 8082
    ├── transactions-service/       # Go,   port 8083
    ├── notifications-service/      # Python, port 8084
    └── web-portal/                 # Next.js, port 3000 (+ optional standalone Ingress)
```

## What you get per service

Every service sub-chart renders:

| Resource | Notes |
|---|---|
| `Deployment` | 2 replicas, rolling updates, `runAsNonRoot`, `readOnlyRootFilesystem` (web-portal opt-out), `seccompProfile: RuntimeDefault`, `drop: ALL` caps |
| `Service` | ClusterIP on the service port |
| `ConfigMap` | All non-secret env (Kafka bootstrap, Postgres host, OTel endpoint, JWT issuer, etc.) |
| `ServiceAccount` | Annotated with `azure.workload.identity/{client-id,tenant-id}` |
| `HorizontalPodAutoscaler` | CPU 70% + memory 80%, min 2 / max 8 |
| `PodDisruptionBudget` | `minAvailable: 1` |
| `NetworkPolicy` | Allows intra-namespace + DNS; ingress-nginx allowed only on the edge services |
| Startup / Readiness / Liveness probes | `/healthz` + `/readyz` |
| Secrets Store CSI volume | Mounted at `/mnt/secrets-store`; synced K8s `Secret` consumed via `envFrom.secretRef` |

The umbrella chart additionally provisions:

- The `msbank` namespace with PodSecurity admission labels (`enforce/audit/warn=restricted`).
- A `SecretProviderClass` (`<release>-microservice-bank-kv`) referencing Key Vault objects
  `jwt-private-key`, `jwt-public-key`, `auth-db-password`, `accounts-db-password`,
  `transactions-db-password`, `notifications-db-password`, `eventhubs-connection-string`,
  `internal-token`, `redis-password`. The CSI driver syncs them into a K8s `Secret`
  named `<release>-microservice-bank-kv-synced`, which every Deployment consumes
  via `envFrom`.
- Default-deny `NetworkPolicy` plus DNS / managed-service egress allow rules.

## Prerequisites

### AKS (production target)

| Component | Why |
|---|---|
| AKS cluster, Kubernetes 1.28+ | Workload Identity GA, PodSecurity admission |
| Azure Container Registry (ACR) | Source for service images; attached to AKS (`az aks update --attach-acr`) |
| Azure Key Vault | Stores all secrets referenced by the SPC |
| Azure Database for PostgreSQL Flexible Server | One DB per service (auth/accounts/transactions/notifications) |
| Azure Event Hubs (Kafka surface) | Backs `KAFKA_BOOTSTRAP_SERVERS` (`*.servicebus.windows.net:9093`, SASL_SSL/PLAIN) |
| Azure Cache for Redis | TLS on 6380 |
| **cert-manager** with a `letsencrypt-prod` ClusterIssuer | Issues TLS certs for the Ingress |
| **ingress-nginx** (or AGIC) | Ingress controller. Switch class via `global.ingress.className` |
| **Secrets Store CSI driver** (`secrets-store.csi.x-k8s.io`) + **Azure provider** | Pulls secrets from Key Vault |
| **Azure Workload Identity** | OIDC federation from each ServiceAccount to a managed identity granted `get` on Key Vault secrets |

One-liner installs (assuming you've already added the official Helm repos):

```bash
# Ingress controller
helm upgrade -i ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-nginx --create-namespace

# cert-manager
helm upgrade -i cert-manager jetstack/cert-manager \
  -n cert-manager --create-namespace --set crds.enabled=true

# Secrets Store CSI driver + Azure provider
helm upgrade -i csi-secrets-store-provider-azure \
  csi-secrets-store-provider-azure/csi-secrets-store-provider-azure \
  -n kube-system
```

Enable AKS Workload Identity + OIDC issuer:

```bash
az aks update -g <rg> -n <aks> \
  --enable-oidc-issuer \
  --enable-workload-identity
```

Then create one Azure managed identity per service, federate each to the
ServiceAccount it will use (`system:serviceaccount:msbank:msbank-<service>`),
and grant `get` on the Key Vault secrets. Copy the client IDs into the
`global.workloadIdentity.clientIds.*` map (or each sub-chart's
`workloadIdentityClientId`).

### Local (kind / minikube)

You only need:

- A cluster (kind, minikube, k3d, …).
- `ingress-nginx` installed (kind has a [recommended manifest](https://kind.sigs.k8s.io/docs/user/ingress/)).
- The bundled Postgres / Kafka / Redis Bitnami sub-charts handle data plane —
  enabled in `values-local.yaml`. No Key Vault / CSI driver required: the
  `keyVault.name` is blanked, so the umbrella's SPC is skipped and Deployments
  consume secrets from an `envFrom.secretRef … optional: true` that you can
  populate with a manual `kubectl create secret` if needed.

## Install

### AKS

```bash
helm dependency update infra/k8s/charts/microservice-bank

helm upgrade -i msbank infra/k8s/charts/microservice-bank \
  -n msbank --create-namespace \
  -f infra/k8s/charts/microservice-bank/values-aks.yaml \
  --set global.image.tag=$(git rev-parse --short HEAD) \
  --set global.keyVault.name=kv-msbank-prod \
  --set global.keyVault.tenantId=$(az account show --query tenantId -o tsv) \
  --set global.workloadIdentity.tenantId=$(az account show --query tenantId -o tsv)
```

### Local (kind / minikube)

```bash
helm dependency update infra/k8s/charts/microservice-bank

helm upgrade -i msbank infra/k8s/charts/microservice-bank \
  -n msbank --create-namespace \
  -f infra/k8s/charts/microservice-bank/values-local.yaml
```

This enables the in-cluster Bitnami `postgresql`, `kafka` (Redpanda-compatible
listener), and `redis` sub-charts. Disable any of them by setting
`postgresql.enabled=false` etc., and point the service URLs at your own infra
via `--set global.postgres.host=…`, `--set global.kafka.bootstrapServers=…`.

### Per-service install / upgrade

Each sub-chart is also standalone-installable for tight dev loops:

```bash
helm upgrade -i api-gateway infra/k8s/charts/api-gateway \
  -n msbank --create-namespace \
  --set global.image.tag=dev-$(date +%s)
```

## Verifying a deployment

```bash
kubectl -n msbank get pods,svc,ingress
kubectl -n msbank rollout status deploy/msbank-api-gateway
kubectl -n msbank get spc,secretproviderclasspodstatus
```

## Linting

```bash
bash infra/k8s/scripts/lint.sh
```

This runs `helm lint` against every sub-chart, builds umbrella deps, then
`helm template` with all three values overlays. Expect only the informational
`Chart.yaml: icon is recommended` notice.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `MountVolume.SetUp failed for volume "secrets-store"` | The pod's ServiceAccount has no federated identity, or Key Vault access policy is missing. Check `kubectl describe pod` + Key Vault → Access policies / RBAC. |
| `failed to get secretproviderclass …` | The SPC name on the Deployment must match the umbrella's. Don't override `nameOverride`/`fullnameOverride` of the umbrella unless you also adjust `svc.spcName`. |
| `ImagePullBackOff` on AKS | Ensure ACR is attached (`az aks update --attach-acr <acr>`), or set `global.image.pullSecrets`. |
| Ingress 404 on `app.<domain>` | `web-portal` sub-chart must be enabled and its Service named `<release>-web-portal`. The api-gateway Ingress assumes the default release name pattern. |
| HPA stuck at `<unknown>/70%` | metrics-server isn't installed (AKS has it by default; for kind/minikube run `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`). |
| Java services OOMKilled | Bump `resources.limits.memory` and pass `-XX:MaxRAMPercentage=75` via `env`. |
| `helm dependency build` fails on Bitnami charts | `helm repo add bitnami https://charts.bitnami.com/bitnami && helm repo update`. |
| PodSecurity admission denies a pod | The umbrella enforces `restricted`. Make sure any user-supplied `extraVolumes`/`env` don't add hostPath, privileged caps, or runAsUser=0. |

## Customisation cheatsheet

| Need | Set |
|---|---|
| Different image tag for one service | `--set api-gateway.image.tag=…` |
| Different domain | `--set global.domain=acme.example.com` |
| Switch to AGIC | `--set global.ingress.className=azure-application-gateway` + relevant annotations in `global.ingress.annotations` |
| Disable network policies | `--set global.networkPolicy.defaultDeny=false --set <svc>.networkPolicy.enabled=false` |
| Disable a service | `--set <name>.enabled=false` |
| Different OTel collector | `--set global.otel.endpoint=http://otel-collector.obs.svc.cluster.local:4317` |

## Design notes

- **No secrets in values files.** Everything that's secret is fetched at pod
  start via the Secrets Store CSI driver from Azure Key Vault, then surfaced
  as env vars via a synced K8s `Secret`. Local dev simply skips the SPC.
- **One SPC, multiple consumers.** The umbrella publishes a single SPC for
  simplicity. If you need per-service identity isolation, render one SPC per
  sub-chart (override the `svc.spcName` helper and add a per-chart SPC
  template) — the rest of the wiring stays the same.
- **PodSecurity `restricted`.** Enforced at the namespace level; matches the
  Deployment `securityContext` defaults so pods always admit cleanly.
- **AKS + zone spread.** `values-aks.yaml` enables a `topologySpreadConstraint`
  on `topology.kubernetes.io/zone` to survive a zonal outage.
- **Sub-charts are independent.** They each declare a `global` block with safe
  defaults so they pass `helm lint` standalone — useful for CI per-service
  preview environments.
