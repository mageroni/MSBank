resource "azurerm_eventhub_namespace" "this" {
  name                = "${var.name_prefix}-ehns"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = var.sku
  capacity            = var.capacity
  tags                = var.tags

  # Kafka protocol surface is implicit-on for Standard+; explicit for clarity.
  # auto_inflate cannot be enabled on Basic SKU.
  auto_inflate_enabled     = var.sku == "Basic" ? false : var.auto_inflate_enabled
  maximum_throughput_units = var.sku == "Basic" ? null : (var.auto_inflate_enabled ? var.maximum_throughput_units : null)

  local_authentication_enabled  = true
  public_network_access_enabled = true
  minimum_tls_version           = "1.2"
}

resource "azurerm_eventhub" "topics" {
  for_each = toset(var.event_hubs)

  name                = each.key
  namespace_name      = azurerm_eventhub_namespace.this.name
  resource_group_name = var.resource_group_name
  partition_count     = var.partition_count
  message_retention   = var.message_retention_days
}

# Per-service Send authorization rule (writes to all topics in the namespace).
resource "azurerm_eventhub_namespace_authorization_rule" "send" {
  for_each = toset(var.services)

  name                = "${each.key}-send"
  namespace_name      = azurerm_eventhub_namespace.this.name
  resource_group_name = var.resource_group_name

  listen = false
  send   = true
  manage = false
}

# Per-service Listen authorization rule.
resource "azurerm_eventhub_namespace_authorization_rule" "listen" {
  for_each = toset(var.services)

  name                = "${each.key}-listen"
  namespace_name      = azurerm_eventhub_namespace.this.name
  resource_group_name = var.resource_group_name

  listen = true
  send   = false
  manage = false
}
