# Remote state backend.
#
# Bootstrap chicken-and-egg: create the storage account manually (or with a
# dedicated bootstrap workspace) before enabling this block. See README.md.
#
# terraform {
#   backend "azurerm" {
#     resource_group_name  = "rg-tfstate"
#     storage_account_name = "sttfstatemsbank"
#     container_name       = "tfstate"
#     key                  = "msbank/<environment>.tfstate"
#     use_azuread_auth     = true
#     # subscription_id    = "<state-subscription-id>"
#     # tenant_id          = "<tenant-id>"
#   }
# }
