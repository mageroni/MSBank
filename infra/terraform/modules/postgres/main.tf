resource "random_password" "admin" {
  length           = 24
  special          = true
  min_special      = 2
  override_special = "!#$%*-_=+"
}

resource "azurerm_private_dns_zone" "postgres" {
  name                = "${var.name_prefix}.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                  = "${var.name_prefix}-pdnsz-link"
  resource_group_name   = var.resource_group_name
  private_dns_zone_name = azurerm_private_dns_zone.postgres.name
  virtual_network_id    = var.virtual_network_id
  tags                  = var.tags
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                          = "${var.name_prefix}-pg"
  location                      = var.location
  resource_group_name           = var.resource_group_name
  version                       = "16"
  delegated_subnet_id           = var.delegated_subnet_id
  private_dns_zone_id           = azurerm_private_dns_zone.postgres.id
  public_network_access_enabled = false

  administrator_login    = var.administrator_login
  administrator_password = random_password.admin.result

  sku_name                     = var.sku_name
  storage_mb                   = var.storage_mb
  backup_retention_days        = 14
  geo_redundant_backup_enabled = false
  zone                         = "1"

  dynamic "high_availability" {
    for_each = var.high_availability ? [1] : []
    content {
      mode                      = "ZoneRedundant"
      standby_availability_zone = "2"
    }
  }

  tags = var.tags

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]
}

resource "azurerm_postgresql_flexible_server_database" "dbs" {
  for_each = toset(var.databases)

  name      = each.key
  server_id = azurerm_postgresql_flexible_server.this.id
  collation = "en_US.utf8"
  charset   = "UTF8"
}
