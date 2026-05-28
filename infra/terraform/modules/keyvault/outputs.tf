output "id" {
  description = "Key Vault resource ID."
  value       = azurerm_key_vault.this.id
}

output "name" {
  description = "Key Vault name."
  value       = azurerm_key_vault.this.name
}

output "vault_uri" {
  description = "Key Vault DNS URI."
  value       = azurerm_key_vault.this.vault_uri
}

output "jwt_public_pem" {
  description = "JWT public key (PEM). Safe to expose; private key remains in Key Vault."
  value       = tls_private_key.jwt.public_key_pem
}
