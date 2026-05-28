variable "name_prefix" {
  description = "Resource name prefix."
  type        = string
}

variable "location" {
  description = "Azure region."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group name."
  type        = string
}

variable "sku_name" {
  description = "Postgres flexible server SKU name."
  type        = string
  default     = "B_Standard_B2ms"
}

variable "storage_mb" {
  description = "Storage size in MB."
  type        = number
  default     = 32768
}

variable "administrator_login" {
  description = "Postgres administrator login name."
  type        = string
  default     = "msbankadmin"
}

variable "delegated_subnet_id" {
  description = "Subnet delegated to Microsoft.DBforPostgreSQL/flexibleServers."
  type        = string
}

variable "virtual_network_id" {
  description = "VNet to link the private DNS zone to."
  type        = string
}

variable "high_availability" {
  description = "Enable ZoneRedundant HA (typically prod-only)."
  type        = bool
  default     = false
}

variable "databases" {
  description = "Database names to create on the server."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
