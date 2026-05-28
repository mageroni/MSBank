# keyvault

Azure Key Vault with RBAC authorization (no access policies), soft delete and
**purge protection** enabled (irreversible -- 90 day minimum).

Role assignments:

- AKS Secrets Store CSI provider identity -> `Key Vault Secrets User`
- The Terraform-runtime principal -> `Key Vault Administrator` (so the
  Terraform apply can seed secrets).

Seeded secrets:

| name | source |
|------|--------|
| `jwt-private-pem` | `tls_private_key` (RSA 2048) |
| `jwt-public-pem`  | derived public key |
| `internal-service-token` | `random_password` (48 chars) |

The caller (root composition) is expected to layer on additional secrets such
as Postgres admin password, Redis primary key, and Event Hubs connection
strings -- those resources live in other modules so their values aren't passed
back through here.

> Quirk: `purge_protection_enabled` cannot be disabled once enabled. For
> ephemeral dev environments you may want to override this.
