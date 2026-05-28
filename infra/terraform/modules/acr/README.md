# acr

Azure Container Registry for the microservice-bank images.

- Admin user disabled (auth via Entra / managed identity).
- Optional geo-replication (Premium SKU only).
- Grants `AcrPull` to the AKS kubelet identity so nodes can pull without secrets.
