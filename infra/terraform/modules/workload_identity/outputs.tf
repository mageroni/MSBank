output "identities" {
  description = "Map of service name -> { client_id, principal_id, resource_id } for each user-assigned identity."
  value = {
    for s, mi in azurerm_user_assigned_identity.svc :
    s => {
      client_id    = mi.client_id
      principal_id = mi.principal_id
      resource_id  = mi.id
      name         = mi.name
    }
  }
}
