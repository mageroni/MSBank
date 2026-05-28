variable "name" {
  description = "ACR name (must be globally unique, alphanumeric, 5-50 chars)."
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

variable "sku" {
  description = "ACR SKU (Basic, Standard, Premium). Geo-replication requires Premium."
  type        = string
  default     = "Premium"
}

variable "geo_replication_locations" {
  description = "Additional locations to geo-replicate the registry to. Requires Premium SKU."
  type        = list(string)
  default     = []
}

variable "aks_kubelet_object_id" {
  description = "Object ID of the AKS kubelet identity (granted AcrPull)."
  type        = string
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
