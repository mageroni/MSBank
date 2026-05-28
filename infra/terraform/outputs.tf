output "aks_kubeconfig" {
  description = "Raw kubeconfig for the AKS cluster (use with kubelogin)."
  value       = module.aks.kube_config_raw
  sensitive   = true
}

output "aks_cluster_name" {
  description = "Name of the AKS cluster."
  value       = module.aks.cluster_name
}

output "acr_login_server" {
  description = "ACR login server hostname (used by Helm imagePullSecrets / image refs)."
  value       = module.acr.login_server
}

output "key_vault_name" {
  description = "Key Vault name (used by Secrets Store CSI SecretProviderClass)."
  value       = module.keyvault.name
}

output "key_vault_uri" {
  description = "Key Vault DNS URI."
  value       = module.keyvault.vault_uri
}

output "postgres_fqdn" {
  description = "Fully qualified hostname of the PostgreSQL Flexible Server."
  value       = module.postgres.fqdn
}

output "eventhubs_namespace_fqdn" {
  description = "Event Hubs namespace FQDN (Kafka bootstrap host:9093)."
  value       = module.eventhubs.namespace_fqdn
}

output "redis_hostname" {
  description = "Redis hostname."
  value       = module.redis.hostname
}

output "app_gateway_public_ip" {
  description = "Public IP of the Application Gateway, if enabled."
  value       = try(module.app_gateway[0].public_ip_address, null)
}
