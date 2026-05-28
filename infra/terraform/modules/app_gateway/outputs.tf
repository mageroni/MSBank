output "id" {
  description = "Application Gateway resource ID."
  value       = azurerm_application_gateway.this.id
}

output "public_ip_address" {
  description = "Public IP address of the Application Gateway."
  value       = azurerm_public_ip.this.ip_address
}

output "identity_principal_id" {
  description = "Principal ID of the user-assigned identity attached to the gateway."
  value       = azurerm_user_assigned_identity.appgw.principal_id
}
