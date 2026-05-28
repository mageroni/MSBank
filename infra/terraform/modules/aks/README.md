# aks

Provisions an Azure Kubernetes Service cluster used by all microservice-bank
workloads.

Highlights:

- Kubernetes 1.29, Azure Linux nodes (`AzureLinux` os_sku).
- Azure CNI **overlay** (`network_plugin_mode = "overlay"`) + Calico network policy.
- System node pool (`only_critical_addons_enabled = true`) is small and isolated;
  user node pool is autoscaling 1-5 nodes.
- `oidc_issuer_enabled` and `workload_identity_enabled` for Entra workload
  identity (consumed by `modules/workload_identity`).
- Secrets Store CSI driver (`key_vault_secrets_provider`) with rotation.
- Azure Policy add-on, Microsoft Defender for Containers, OMS agent — all
  wired to the Log Analytics workspace.
- `identity { type = "SystemAssigned" }`. Kubelet identity object ID is
  exported so `modules/acr` can grant `AcrPull`.

> Quirk: switching `network_plugin_mode` from `overlay` to anything else
> forces cluster replacement. Pod CIDR (`172.17.0.0/16`) is only valid in
> overlay mode.
