# microservice-bank :: Terraform (Azure)

Illustrative Terraform skeleton for deploying the **microservice-bank** stack
onto Microsoft Azure. Pairs with the Helm chart at
`infra/k8s/charts/microservice-bank/`.

> Status: skeleton only. Syntactically valid (`terraform fmt -check`,
> `terraform validate`), **not** intended to be applied as-is. Sanity-check
> every SKU/CIDR/region for your target.

## Architecture

```
                                +-------------------------------+
       Internet  ───────────▶   |  Application Gateway v2 (WAF) |  (optional)
                                +---------------┬---------------+
                                                │ HTTPS
                                                ▼
+---------------------- VNet 10.10.0.0/16 ---------------------------------+
|                                                                          |
|  snet-aks 10.10.0.0/22       snet-appgw 10.10.4.0/24                     |
|  ┌────────────────────────┐  ┌──────────────────┐                        |
|  │ AKS (1.29, Azure CNI   │  │ App Gateway      │                        |
|  │ overlay + Calico)      │  │ + Public IP      │                        |
|  │  ┌──────────────────┐  │  └──────────────────┘                        |
|  │  │ msbank ns        │  │                                              |
|  │  │  - api-gateway   │  │  snet-pe 10.10.5.0/24 (delegated)            |
|  │  │  - auth-service  │  │  ┌───────────────────┐                       |
|  │  │  - accounts-svc  │  │  │ Postgres Flex 16  │                       |
|  │  │  - transactions  │  │  │  (private)        │                       |
|  │  │  - notifications │  │  └───────────────────┘                       |
|  │  └──────────────────┘  │  ┌───────────────────┐                       |
|  └────────────────────────┘  │ Redis (Std C1)    │                       |
|         │ Workload ID         └───────────────────┘                      |
|         ▼                                                                |
|  +--------------+   +-----------+   +---------------------+              |
|  | Key Vault    |   | ACR (Std) |   | Event Hubs (Kafka)  |              |
|  | (RBAC, KV    |   | AcrPull   |   |  user/account/      |              |
|  |  CSI driver) |   | -> AKS    |   |  transaction/       |              |
|  +--------------+   +-----------+   |  notification-events|              |
|                                     +---------------------+              |
|                                                                          |
|  Log Analytics Workspace + Application Insights (workspace-based)        |
+--------------------------------------------------------------------------+
```

## Prerequisites

- Azure CLI (>= 2.60), logged in with **Owner** on the target subscription
  (`az login && az account set -s <SUBSCRIPTION_ID>`).
- [`terraform`](https://developer.hashicorp.com/terraform/install) >= 1.7.
- [`kubelogin`](https://azure.github.io/kubelogin/) -- AKS uses Entra
  authentication; vanilla kubeconfig will not work without it.
- (Optional, for Helm step) `kubectl`, `helm` >= 3.14.

## Layout

```
infra/terraform/
├── versions.tf        provider version pins
├── providers.tf       provider configuration
├── variables.tf       inputs to the root composition
├── locals.tf          naming + tags
├── main.tf            composes the modules
├── outputs.tf         outputs consumed downstream (Helm, CI)
├── backend.tf         (commented-out) azurerm remote state
├── modules/
│   ├── resource_group/
│   ├── network/
│   ├── aks/
│   ├── acr/
│   ├── postgres/
│   ├── eventhubs/
│   ├── redis/
│   ├── keyvault/
│   ├── observability/
│   ├── app_gateway/
│   └── workload_identity/
└── environments/
    ├── dev/  (terraform.tfvars.example, backend.tfvars.example)
    └── prod/
```

## Bootstrap remote state (chicken-and-egg)

The `azurerm` backend cannot create its own storage account. Bootstrap it
**out of band** with the Azure CLI (recommended) the first time:

```bash
SUB_ID="<your-subscription>"
LOC="eastus2"
RG="rg-tfstate"
STG="sttfstatemsbank$RANDOM"   # globally unique

az group create -n "$RG" -l "$LOC"
az storage account create -g "$RG" -n "$STG" -l "$LOC" \
    --sku Standard_LRS --kind StorageV2 \
    --allow-blob-public-access false \
    --min-tls-version TLS1_2
az storage container create --account-name "$STG" -n tfstate --auth-mode login
```

Then update `environments/<env>/backend.tfvars.example` and uncomment the
`backend "azurerm"` block in `backend.tf`. Initialize with:

```bash
cd environments/dev
terraform init -backend-config=backend.tfvars
```

## Apply

```bash
cd infra/terraform/environments/dev
cp terraform.tfvars.example terraform.tfvars   # then edit
terraform init                                  # local state until backend is bootstrapped
terraform plan  -out tfplan
terraform apply tfplan
```

Expected key outputs:

| output | used by |
|--------|---------|
| `aks_kubeconfig` (sensitive) | `kubectl`, `helm` -- `kubelogin` re-auths transparently |
| `acr_login_server` | Helm `image.registry` value |
| `key_vault_name` / `key_vault_uri` | Secrets Store CSI `SecretProviderClass` |
| `postgres_fqdn` | service DB connection strings |
| `eventhubs_namespace_fqdn` | Kafka bootstrap (`<fqdn>:9093`) |
| `redis_hostname` | cache config |
| `app_gateway_public_ip` | DNS record for the entry point (if enabled) |

## Hand-off to Helm

```bash
terraform -chdir=infra/terraform/environments/dev output -raw aks_kubeconfig \
  > ~/.kube/config-msbank
export KUBECONFIG=~/.kube/config-msbank
kubelogin convert-kubeconfig -l azurecli

helm upgrade --install msbank ../../k8s/charts/microservice-bank \
  --namespace msbank --create-namespace \
  --set image.registry=$(terraform -chdir=infra/terraform/environments/dev output -raw acr_login_server) \
  --set keyVault.name=$(terraform -chdir=infra/terraform/environments/dev output -raw key_vault_name) \
  --set postgres.host=$(terraform -chdir=infra/terraform/environments/dev output -raw postgres_fqdn) \
  --set eventHubs.bootstrap=$(terraform -chdir=infra/terraform/environments/dev output -raw eventhubs_namespace_fqdn):9093 \
  --set redis.host=$(terraform -chdir=infra/terraform/environments/dev output -raw redis_hostname)
```

## Rough monthly cost (dev, eastus2, list prices ~Q3 2024)

| component | SKU | est. USD/mo |
|-----------|-----|-------------|
| AKS control plane | Free tier | $0 |
| AKS nodes | 2× Standard_D2s_v5 | ~$140 |
| ACR | Standard | ~$20 |
| Postgres Flex | B_Standard_B2ms, 32 GiB | ~$80 |
| Event Hubs | Standard, 1 TU | ~$22 |
| Redis | Standard C1 | ~$75 |
| Key Vault | Standard | <$5 |
| Log Analytics | 30-day retention, ~5 GB/day | ~$30 |
| **Total (dev)** | | **~$370/mo** |

Production with HA Postgres, Premium Redis, App Gateway WAF_v2, and Premium
Event Hubs trends to **~$2,000–3,000/mo**. Plug actual SKUs into the Azure
pricing calculator for a real estimate.

## Teardown

```bash
cd infra/terraform/environments/dev
terraform destroy
```

Key Vault has **purge protection** -- the soft-deleted vault stays
recoverable for 7 days and the *name* is reserved for 90. Use a fresh
`prefix` or wait, or `az keyvault purge` if your tenant allows it.

## Provider quirks to flag

- **AKS `network_plugin_mode = "overlay"`** is one-way; cannot switch back to
  classic Azure CNI without rebuilding the cluster.
- **Postgres Flex** delegated subnet cannot host other resource types (the
  Microsoft.DBforPostgreSQL delegation owns the entire subnet). Recreating
  the server requires a fresh subnet *or* full destroy/recreate of the link.
- **Key Vault purge protection** is irreversible (see Teardown).
- **App Gateway + AGIC** mutates many gateway fields at runtime; this module
  sets `lifecycle.ignore_changes` on the affected sub-blocks. Strip those
  out if you are *not* using AGIC.
- **Event Hubs** `auto_inflate_enabled` is invalid on the Basic SKU; module
  guards for this. Premium uses processing units (PUs) instead of TUs --
  override `capacity` accordingly.
- The **`azapi`** provider is included for future resources that lag the
  `azurerm` schema (e.g., preview AKS features). Not used by the skeleton
  modules today.
- AKS `default_node_pool.os_sku = "AzureLinux"` requires AKS 1.27+ and
  forces replacement when changed.
