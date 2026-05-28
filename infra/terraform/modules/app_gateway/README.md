# app_gateway

Application Gateway v2 (WAF_v2 SKU, OWASP 3.2, Prevention mode), zone-redundant.

- Static Standard SKU public IP, zones 1/2/3.
- HTTP listener (80) -> permanent redirect to HTTPS listener (443).
- HTTPS listener references a Key Vault secret for the TLS cert (`tls_key_vault_secret_id`).
- Backend pool starts empty; populate with the AKS ingress IP, or enable AGIC
  (Application Gateway Ingress Controller) and let it manage pools/listeners
  at runtime. `lifecycle.ignore_changes` is set accordingly.

> Quirk: when AGIC is in use it overwrites large portions of the gateway
> config on each reconcile. Terraform must ignore those fields or every plan
> will show drift. The `ignore_changes` list here is conservative -- adjust if
> you are *not* using AGIC.
