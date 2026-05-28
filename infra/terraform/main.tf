module "resource_group" {
  source = "./modules/resource_group"

  name     = "${local.name_prefix}-rg"
  location = var.location
  tags     = local.tags
}

module "network" {
  source = "./modules/network"

  name_prefix         = local.name_prefix
  location            = var.location
  resource_group_name = module.resource_group.name
  tags                = local.tags
}

module "observability" {
  source = "./modules/observability"

  name_prefix         = local.name_prefix
  location            = var.location
  resource_group_name = module.resource_group.name
  retention_days      = var.log_retention_days
  tags                = local.tags
}

module "acr" {
  source = "./modules/acr"

  name                  = "${var.prefix}${var.environment}acr${local.globally_unique_suffix}"
  location              = var.location
  resource_group_name   = module.resource_group.name
  sku                   = var.acr_sku
  aks_kubelet_object_id = module.aks.kubelet_identity_object_id
  tags                  = local.tags
}

module "aks" {
  source = "./modules/aks"

  name_prefix                = local.name_prefix
  location                   = var.location
  resource_group_name        = module.resource_group.name
  subnet_id                  = module.network.aks_subnet_id
  node_count                 = var.aks_node_count
  node_vm_size               = var.aks_node_vm_size
  log_analytics_workspace_id = module.observability.log_analytics_workspace_id
  authorized_ip_ranges       = var.allowed_admin_cidrs
  tags                       = local.tags
}

module "keyvault" {
  source = "./modules/keyvault"

  name                       = "${var.prefix}-${var.environment}-kv-${local.globally_unique_suffix}"
  location                   = var.location
  resource_group_name        = module.resource_group.name
  tenant_id                  = var.tenant_id
  secrets_provider_object_id = module.aks.secrets_provider_object_id
  tags                       = local.tags
}

module "postgres" {
  source = "./modules/postgres"

  name_prefix         = local.name_prefix
  location            = var.location
  resource_group_name = module.resource_group.name
  sku_name            = var.postgres_sku
  delegated_subnet_id = module.network.private_endpoints_subnet_id
  virtual_network_id  = module.network.vnet_id
  high_availability   = var.environment == "prod"
  databases           = local.postgres_databases
  tags                = local.tags
}

module "eventhubs" {
  source = "./modules/eventhubs"

  name_prefix         = local.name_prefix
  location            = var.location
  resource_group_name = module.resource_group.name
  sku                 = var.event_hubs_sku
  event_hubs          = local.event_hubs
  services            = local.services
  tags                = local.tags
}

module "redis" {
  source = "./modules/redis"

  name                = "${local.name_prefix}-redis-${local.globally_unique_suffix}"
  location            = var.location
  resource_group_name = module.resource_group.name
  sku                 = var.redis_sku
  tags                = local.tags
}

module "app_gateway" {
  source = "./modules/app_gateway"
  count  = var.enable_app_gateway ? 1 : 0

  name_prefix         = local.name_prefix
  location            = var.location
  resource_group_name = module.resource_group.name
  subnet_id           = module.network.app_gateway_subnet_id
  tags                = local.tags
}

module "workload_identity" {
  source = "./modules/workload_identity"

  name_prefix            = local.name_prefix
  location               = var.location
  resource_group_name    = module.resource_group.name
  oidc_issuer_url        = module.aks.oidc_issuer_url
  services               = local.services
  eventhubs_namespace_id = module.eventhubs.namespace_id
  key_vault_id           = module.keyvault.id
  tags                   = local.tags
}
