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

variable "sku" {
  description = "Event Hubs namespace SKU (Basic, Standard, Premium)."
  type        = string
  default     = "Standard"
}

variable "capacity" {
  description = "Throughput units (Standard) or processing units (Premium)."
  type        = number
  default     = 1
}

variable "auto_inflate_enabled" {
  description = "Whether to auto-inflate throughput units. Ignored on Basic SKU."
  type        = bool
  default     = false
}

variable "maximum_throughput_units" {
  description = "Max TUs when auto-inflate is enabled."
  type        = number
  default     = 4
}

variable "event_hubs" {
  description = "Event hub (topic) names to create."
  type        = list(string)
  default     = []
}

variable "services" {
  description = "Service names; for each, a Send and Listen authorization rule is created."
  type        = list(string)
  default     = []
}

variable "partition_count" {
  description = "Partitions per event hub."
  type        = number
  default     = 4
}

variable "message_retention_days" {
  description = "Retention (days) per event hub."
  type        = number
  default     = 1
}

variable "tags" {
  description = "Tags to apply."
  type        = map(string)
  default     = {}
}
