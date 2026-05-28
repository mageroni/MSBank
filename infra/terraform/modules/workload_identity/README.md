# workload_identity

For each service in `services`, this module provisions:

1. A User-Assigned Managed Identity (`<prefix>-<svc>-mi`).
2. A Federated Identity Credential trusting
   `system:serviceaccount:<namespace>:<svc>` from the AKS OIDC issuer.
3. Role assignments:
   - `Key Vault Secrets User` on the shared Key Vault for every service.
   - `Azure Event Hubs Data Sender` on the Event Hubs namespace for
     producers (`auth-service`, `accounts-service`, `transactions-service`).
   - `Azure Event Hubs Data Receiver` on the Event Hubs namespace for
     `notifications-service`.

The Helm chart should set on each Deployment:

```yaml
serviceAccount:
  name: <service>
  annotations:
    azure.workload.identity/client-id: <client_id from this module>
podLabels:
  azure.workload.identity/use: "true"
```

> Quirk: `azurerm_federated_identity_credential` requires the AKS OIDC issuer
> URL **without** a trailing slash. AKS returns it that way; if you ever
> manually normalize it, drop the slash.
