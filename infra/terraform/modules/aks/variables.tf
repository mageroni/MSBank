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
  description = "Subnet ID for AKS node pools."
  type        = string
}

variable "node_count" {
  description = "Initial node count for the user node pool."
  type        = number
  default     = 2
}

variable "node_vm_size" {
  description = "VM size for AKS node pools."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "kubernetes_version" {
  description = "Kubernetes minor version."
  type        = string
  default     = "1.29"
}

variable "log_analytics_workspace_id" {
  description = "Log Analytics workspace ID for OMS agent and Defender."
  type        = string
}

variable "authorized_ip_ranges" {
  description = "CIDRs allowed to reach the AKS API server."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
