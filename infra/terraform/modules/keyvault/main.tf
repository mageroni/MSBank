data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "this" {
  name                = var.name
  location            = var.location
  resource_group_name = var.resource_group_name
  tenant_id           = var.tenant_id
  sku_name            = var.sku_name

  enable_rbac_authorization     = true
  purge_protection_enabled      = true
  soft_delete_retention_days    = 7
  public_network_access_enabled = true

  tags = var.tags
}

# CSI secrets-provider identity gets read access at the vault scope.
resource "azurerm_role_assignment" "csi_secrets_user" {
  count = var.secrets_provider_object_id == null ? 0 : 1

  scope                            = azurerm_key_vault.this.id
  role_definition_name             = "Key Vault Secrets User"
  principal_id                     = var.secrets_provider_object_id
  skip_service_principal_aad_check = true
}

# Terraform-runtime identity needs admin to seed secrets.
resource "azurerm_role_assignment" "deployer_admin" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Administrator"
  principal_id         = data.azurerm_client_config.current.object_id
}

# JWT signing keypair generated at apply time and stored in KV.
resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "azurerm_key_vault_secret" "jwt_private_pem" {
  name         = "jwt-private-pem"
  value        = tls_private_key.jwt.private_key_pem
  key_vault_id = azurerm_key_vault.this.id
  content_type = "application/x-pem-file"

  depends_on = [azurerm_role_assignment.deployer_admin]
}

resource "azurerm_key_vault_secret" "jwt_public_pem" {
  name         = "jwt-public-pem"
  value        = tls_private_key.jwt.public_key_pem
  key_vault_id = azurerm_key_vault.this.id
  content_type = "application/x-pem-file"

  depends_on = [azurerm_role_assignment.deployer_admin]
}

resource "random_password" "internal_token" {
  length  = 48
  special = false
}

resource "azurerm_key_vault_secret" "internal_token" {
  name         = "internal-service-token"
  value        = random_password.internal_token.result
  key_vault_id = azurerm_key_vault.this.id

  depends_on = [azurerm_role_assignment.deployer_admin]
}
