variable "name" {
  description = "Key Vault name (globally unique, 3-24 chars, alphanumeric + hyphens)."
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

variable "tenant_id" {
  description = "Entra ID tenant ID."
  type        = string
}

variable "sku_name" {
  description = "Key Vault SKU (standard or premium)."
  type        = string
  default     = "standard"
}

variable "secrets_provider_object_id" {
  description = "Object ID of the AKS Secrets Store CSI provider identity (granted Key Vault Secrets User)."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
