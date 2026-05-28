output "cluster_name" {
  description = "AKS cluster name."
  value       = azurerm_kubernetes_cluster.this.name
}

output "cluster_id" {
  description = "AKS cluster resource ID."
  value       = azurerm_kubernetes_cluster.this.id
}

output "oidc_issuer_url" {
  description = "OIDC issuer URL (used to federate workload identities)."
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
}

output "kubelet_identity_object_id" {
  description = "Object ID of the kubelet user-assigned identity (used for AcrPull)."
  value       = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}

output "secrets_provider_object_id" {
  description = "Object ID of the AKS Secrets Store CSI provider managed identity."
  value       = azurerm_kubernetes_cluster.this.key_vault_secrets_provider[0].secret_identity[0].object_id
}

output "kube_config_raw" {
  description = "Raw kubeconfig for the cluster."
  value       = azurerm_kubernetes_cluster.this.kube_config_raw
  sensitive   = true
}

output "node_resource_group" {
  description = "Auto-generated node resource group (MC_*)."
  value       = azurerm_kubernetes_cluster.this.node_resource_group
}
