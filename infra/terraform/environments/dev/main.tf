module "msbank" {
  source = "../.."

  subscription_id = var.subscription_id
  tenant_id       = var.tenant_id

  environment        = "dev"
  location           = "eastus2"
  prefix             = "msbank"
  aks_node_count     = 2
  aks_node_vm_size   = "Standard_D2s_v5"
  postgres_sku       = "B_Standard_B2ms"
  event_hubs_sku     = "Standard"
  acr_sku            = "Standard"
  enable_app_gateway = false
  log_retention_days = 30

  redis_sku = {
    name     = "Standard"
    family   = "C"
    capacity = 1
  }

  allowed_admin_cidrs = var.allowed_admin_cidrs

  tags = {
    cost_center = "engineering"
  }
}

variable "subscription_id" {
  type = string
}

variable "tenant_id" {
  type = string
}

variable "allowed_admin_cidrs" {
  type    = list(string)
  default = []
}

output "aks_cluster_name" {
  value = module.msbank.aks_cluster_name
}

output "acr_login_server" {
  value = module.msbank.acr_login_server
}

output "key_vault_name" {
  value = module.msbank.key_vault_name
}

output "postgres_fqdn" {
  value = module.msbank.postgres_fqdn
}

output "eventhubs_namespace_fqdn" {
  value = module.msbank.eventhubs_namespace_fqdn
}

output "redis_hostname" {
  value = module.msbank.redis_hostname
}
