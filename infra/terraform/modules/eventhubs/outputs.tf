output "namespace_id" {
  description = "Event Hubs namespace resource ID."
  value       = azurerm_eventhub_namespace.this.id
}

output "namespace_name" {
  description = "Event Hubs namespace name."
  value       = azurerm_eventhub_namespace.this.name
}

output "namespace_fqdn" {
  description = "Kafka bootstrap FQDN for the namespace (port 9093)."
  value       = "${azurerm_eventhub_namespace.this.name}.servicebus.windows.net"
}

output "event_hub_names" {
  description = "Names of event hubs (topics) created."
  value       = [for h in azurerm_eventhub.topics : h.name]
}

output "send_connection_strings" {
  description = "Per-service Send connection strings."
  value = {
    for k, r in azurerm_eventhub_namespace_authorization_rule.send :
    k => r.primary_connection_string
  }
  sensitive = true
}

output "listen_connection_strings" {
  description = "Per-service Listen connection strings."
  value = {
    for k, r in azurerm_eventhub_namespace_authorization_rule.listen :
    k => r.primary_connection_string
  }
  sensitive = true
}
