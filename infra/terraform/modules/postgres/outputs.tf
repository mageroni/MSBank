output "server_id" {
  description = "Postgres flexible server resource ID."
  value       = azurerm_postgresql_flexible_server.this.id
}

output "fqdn" {
  description = "Fully qualified hostname of the Postgres server."
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "administrator_login" {
  description = "Postgres administrator login."
  value       = azurerm_postgresql_flexible_server.this.administrator_login
}

output "administrator_password" {
  description = "Generated Postgres administrator password (push to Key Vault from caller)."
  value       = random_password.admin.result
  sensitive   = true
}

output "database_names" {
  description = "Names of databases created on the server."
  value       = [for d in azurerm_postgresql_flexible_server_database.dbs : d.name]
}
