resource "azurerm_user_assigned_identity" "svc" {
  for_each = toset(var.services)

  name                = "${var.name_prefix}-${each.key}-mi"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_federated_identity_credential" "svc" {
  for_each = toset(var.services)

  name                = "${var.name_prefix}-${each.key}-fic"
  resource_group_name = var.resource_group_name
  audience            = ["api://AzureADTokenExchange"]
  issuer              = var.oidc_issuer_url
  parent_id           = azurerm_user_assigned_identity.svc[each.key].id
  subject             = "system:serviceaccount:${var.kubernetes_namespace}:${each.key}"
}

# Notifications service: receive from notification-events / transaction-events.
resource "azurerm_role_assignment" "notifications_receiver" {
  count = contains(var.services, "notifications-service") ? 1 : 0

  scope                = var.eventhubs_namespace_id
  role_definition_name = "Azure Event Hubs Data Receiver"
  principal_id         = azurerm_user_assigned_identity.svc["notifications-service"].principal_id
}

# All services can read secrets from Key Vault.
resource "azurerm_role_assignment" "kv_secrets_user" {
  for_each = toset(var.services)

  scope                = var.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.svc[each.key].principal_id
}

# Services that produce to Event Hubs get the Sender role at namespace scope.
resource "azurerm_role_assignment" "eh_sender" {
  for_each = toset([
    for s in var.services : s
    if contains(["auth-service", "accounts-service", "transactions-service"], s)
  ])

  scope                = var.eventhubs_namespace_id
  role_definition_name = "Azure Event Hubs Data Sender"
  principal_id         = azurerm_user_assigned_identity.svc[each.key].principal_id
}
