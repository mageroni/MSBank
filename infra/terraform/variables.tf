variable "subscription_id" {
  description = "Azure subscription ID where resources will be deployed."
  type        = string
}

variable "tenant_id" {
  description = "Azure Active Directory tenant ID."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev, staging, prod)."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}

variable "location" {
  description = "Azure region for primary deployment."
  type        = string
  default     = "eastus2"
}

variable "prefix" {
  description = "Short prefix used in resource names."
  type        = string
  default     = "msbank"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{2,9}$", var.prefix))
    error_message = "prefix must be 3-10 chars, lowercase alphanumeric, starting with a letter."
  }
}

variable "tags" {
  description = "Additional tags to merge into the default tag map."
  type        = map(string)
  default     = {}
}

variable "aks_node_count" {
  description = "Initial node count for the AKS user node pool."
  type        = number
  default     = 2
}

variable "aks_node_vm_size" {
  description = "VM size for AKS node pools."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "postgres_sku" {
  description = "SKU name for Azure Database for PostgreSQL Flexible Server (e.g. B_Standard_B1ms, GP_Standard_D2s_v3)."
  type        = string
  default     = "B_Standard_B2ms"
}

variable "redis_sku" {
  description = "Azure Cache for Redis SKU object."
  type = object({
    name     = string
    family   = string
    capacity = number
  })
  default = {
    name     = "Standard"
    family   = "C"
    capacity = 1
  }
}

variable "event_hubs_sku" {
  description = "SKU for the Event Hubs namespace (Basic, Standard, Premium)."
  type        = string
  default     = "Standard"
}

variable "enable_app_gateway" {
  description = "Whether to provision an Application Gateway in front of AKS."
  type        = bool
  default     = false
}

variable "allowed_admin_cidrs" {
  description = "CIDRs permitted to reach administrative endpoints (e.g., AKS API, Postgres firewall)."
  type        = list(string)
  default     = []
}

variable "acr_sku" {
  description = "SKU for Azure Container Registry (Basic, Standard, Premium)."
  type        = string
  default     = "Premium"
}

variable "log_retention_days" {
  description = "Retention period (days) for Log Analytics Workspace."
  type        = number
  default     = 30
}
