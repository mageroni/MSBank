# network

VNet `10.10.0.0/16` with three subnets:

| subnet | CIDR | purpose |
|--------|------|---------|
| `snet-aks` | `10.10.0.0/22` | AKS node pools (Azure CNI overlay) |
| `snet-appgw` | `10.10.4.0/24` | Application Gateway v2 |
| `snet-pe` | `10.10.5.0/24` | Private endpoints / delegated to `Microsoft.DBforPostgreSQL/flexibleServers` |

Two NSGs are created (AKS, App Gateway) with the minimum rules required for
App Gateway v2 (HTTPS inbound + GatewayManager 65200-65535). AKS NSG starts
empty -- AKS-managed rules attach automatically.

> Subnet delegation note: Postgres flex consumes the subnet's delegation.
> If you want to host arbitrary private endpoints there too, set
> `private_endpoint_network_policies = "Disabled"` (already configured).
> For production, split private endpoints onto a dedicated subnet.
