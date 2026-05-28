variable "name" {
  description = "Redis cache name."
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
  description = "Redis SKU triple {name, family, capacity}."
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

variable "enable_private_endpoint" {
  description = "Create a Private Endpoint and disable public access."
  type        = bool
  default     = false
}

variable "private_endpoint_subnet_id" {
  description = "Subnet ID for the private endpoint (required when enabled)."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
