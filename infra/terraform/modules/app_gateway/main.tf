resource "azurerm_public_ip" "this" {
  name                = "${var.name_prefix}-appgw-pip"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = ["1", "2", "3"]
  tags                = var.tags
}

resource "azurerm_user_assigned_identity" "appgw" {
  name                = "${var.name_prefix}-appgw-mi"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

locals {
  frontend_ip_name      = "frontend-ip"
  frontend_port_http    = "port-80"
  frontend_port_https   = "port-443"
  backend_pool_name     = "aks-ingress-pool"
  backend_settings_name = "aks-ingress-http-settings"
  listener_http         = "http-listener"
  listener_https        = "https-listener"
  redirect_name         = "http-to-https"
  routing_rule_http     = "rule-http-redirect"
  routing_rule_https    = "rule-https"
  ssl_cert_name         = "msbank-tls"
}

resource "azurerm_application_gateway" "this" {
  name                = "${var.name_prefix}-appgw"
  location            = var.location
  resource_group_name = var.resource_group_name
  zones               = ["1", "2", "3"]
  tags                = var.tags

  sku {
    name = "WAF_v2"
    tier = "WAF_v2"
  }

  autoscale_configuration {
    min_capacity = 1
    max_capacity = 3
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.appgw.id]
  }

  gateway_ip_configuration {
    name      = "gateway-ip"
    subnet_id = var.subnet_id
  }

  frontend_ip_configuration {
    name                 = local.frontend_ip_name
    public_ip_address_id = azurerm_public_ip.this.id
  }

  frontend_port {
    name = local.frontend_port_http
    port = 80
  }

  frontend_port {
    name = local.frontend_port_https
    port = 443
  }

  backend_address_pool {
    name = local.backend_pool_name
    # Populated later by AGIC / external-dns or set manually to ingress IP.
  }

  backend_http_settings {
    name                  = local.backend_settings_name
    cookie_based_affinity = "Disabled"
    port                  = 80
    protocol              = "Http"
    request_timeout       = 30
  }

  http_listener {
    name                           = local.listener_http
    frontend_ip_configuration_name = local.frontend_ip_name
    frontend_port_name             = local.frontend_port_http
    protocol                       = "Http"
  }

  http_listener {
    name                           = local.listener_https
    frontend_ip_configuration_name = local.frontend_ip_name
    frontend_port_name             = local.frontend_port_https
    protocol                       = "Https"
    ssl_certificate_name           = local.ssl_cert_name
  }

  # Placeholder cert sourced from Key Vault. Wire `key_vault_secret_id`
  # to a real cert (PFX) before applying.
  ssl_certificate {
    name                = local.ssl_cert_name
    key_vault_secret_id = var.tls_key_vault_secret_id
  }

  redirect_configuration {
    name                 = local.redirect_name
    redirect_type        = "Permanent"
    target_listener_name = local.listener_https
    include_path         = true
    include_query_string = true
  }

  request_routing_rule {
    name                        = local.routing_rule_http
    rule_type                   = "Basic"
    http_listener_name          = local.listener_http
    redirect_configuration_name = local.redirect_name
    priority                    = 100
  }

  request_routing_rule {
    name                       = local.routing_rule_https
    rule_type                  = "Basic"
    http_listener_name         = local.listener_https
    backend_address_pool_name  = local.backend_pool_name
    backend_http_settings_name = local.backend_settings_name
    priority                   = 110
  }

  waf_configuration {
    enabled          = true
    firewall_mode    = "Prevention"
    rule_set_type    = "OWASP"
    rule_set_version = "3.2"
  }

  lifecycle {
    # AGIC mutates backend pools / probes at runtime.
    ignore_changes = [
      backend_address_pool,
      backend_http_settings,
      http_listener,
      probe,
      request_routing_rule,
      url_path_map,
      ssl_certificate,
      redirect_configuration,
      frontend_port,
    ]
  }
}
