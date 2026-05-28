output "id" {
  description = "ACR resource ID."
  value       = azurerm_container_registry.this.id
}

output "name" {
  description = "ACR name."
  value       = azurerm_container_registry.this.name
}

output "login_server" {
  description = "ACR login server hostname."
  value       = azurerm_container_registry.this.login_server
}
