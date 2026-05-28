# postgres

Azure Database for PostgreSQL **Flexible Server** v16, deployed VNet-integrated
(public access disabled) onto the caller-supplied `delegated_subnet_id`. A
private DNS zone (`<prefix>.postgres.database.azure.com`) is created and
VNet-linked so workloads inside the cluster resolve the FQDN to the private IP.

- 4 databases: `auth`, `accounts`, `transactions`, `notifications`.
- `high_availability = true` provisions ZoneRedundant standby in zone 2
  (prod-only by default).
- Backup retention: 14 days.
- Admin password generated with `random_password`; expected to be pushed into
  Key Vault by the root composition.

> Quirk: changing `delegated_subnet_id` or `private_dns_zone_id` forces
> recreation. `azurerm` requires the private DNS zone VNet link to be in place
> *before* the server is created -- enforced here via `depends_on`.
