locals {
  name_prefix = "${var.prefix}-${var.environment}"

  default_tags = {
    app         = "msbank"
    environment = var.environment
    managed_by  = "terraform"
    owner       = "platform-team"
  }

  tags = merge(local.default_tags, var.tags)

  # Stable short suffix used by globally-unique names (KV, ACR, storage).
  globally_unique_suffix = substr(sha1("${var.prefix}-${var.environment}-${var.subscription_id}"), 0, 6)

  services = [
    "auth-service",
    "accounts-service",
    "transactions-service",
    "notifications-service",
    "api-gateway",
  ]

  postgres_databases = [
    "auth",
    "accounts",
    "transactions",
    "notifications",
  ]

  event_hubs = [
    "user-events",
    "account-events",
    "transaction-events",
    "notification-events",
  ]
}
