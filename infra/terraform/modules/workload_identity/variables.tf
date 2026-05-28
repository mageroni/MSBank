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

variable "oidc_issuer_url" {
  description = "AKS OIDC issuer URL."
  type        = string
}

variable "kubernetes_namespace" {
  description = "Kubernetes namespace hosting the workload service accounts."
  type        = string
  default     = "msbank"
}

variable "services" {
  description = "Service names (each maps to a K8s ServiceAccount of the same name)."
  type        = list(string)
}

variable "eventhubs_namespace_id" {
  description = "Event Hubs namespace ID (scope for Sender/Receiver role assignments)."
  type        = string
}

variable "key_vault_id" {
  description = "Key Vault resource ID (scope for Secrets User role assignments)."
  type        = string
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
