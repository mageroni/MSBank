module "msbank" {
  source = "../.."

  subscription_id = var.subscription_id
  tenant_id       = var.tenant_id

  environment        = "prod"
  location           = "eastus2"
  prefix             = "msbank"
  aks_node_count     = 3
  aks_node_vm_size   = "Standard_D4s_v5"
  postgres_sku       = "GP_Standard_D4s_v3"
  event_hubs_sku     = "Premium"
  acr_sku            = "Premium"
  enable_app_gateway = true
  log_retention_days = 90

  redis_sku = {
    name     = "Premium"
    family   = "P"
    capacity = 1
  }

  allowed_admin_cidrs = var.allowed_admin_cidrs

  tags = {
    cost_center = "production"
    compliance  = "pci"
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

output "app_gateway_public_ip" {
  value = module.msbank.app_gateway_public_ip
}
