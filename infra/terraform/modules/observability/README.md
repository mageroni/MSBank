# observability

- Log Analytics Workspace (`PerGB2018`, configurable retention).
- Application Insights (workspace-based) writing to the same workspace.

Other modules (AKS, App Gateway, Redis) wire diagnostic settings to the
workspace ID exported here. Application Insights connection string is sensitive
and intended to be pushed to Key Vault for workloads to consume.
