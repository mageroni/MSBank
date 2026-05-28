output "id" {
  description = "Redis cache resource ID."
  value       = azurerm_redis_cache.this.id
}

output "hostname" {
  description = "Redis hostname."
  value       = azurerm_redis_cache.this.hostname
}

output "ssl_port" {
  description = "Redis SSL port (typically 6380)."
  value       = azurerm_redis_cache.this.ssl_port
}

output "primary_access_key" {
  description = "Redis primary access key."
  value       = azurerm_redis_cache.this.primary_access_key
  sensitive   = true
}
