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

variable "subnet_id" {
  description = "Subnet ID dedicated to App Gateway v2."
  type        = string
}

variable "tls_key_vault_secret_id" {
  description = "Key Vault secret ID for the TLS PFX. Use a placeholder until a real cert is uploaded."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
