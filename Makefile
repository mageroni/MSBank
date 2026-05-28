SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker/docker-compose.yml --env-file .env

.PHONY: help
help: ## Show this help
	@awk 'BEGIN{FS=":.*##"; printf "\nUsage: make <target>\n\nTargets:\n"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

.PHONY: env
env: ## Create .env from example if missing
	@test -f .env || (cp .env.example .env && echo "Created .env from .env.example")

.PHONY: up
up: env ## Start the full stack (compose)
	$(COMPOSE) up -d --build

.PHONY: down
down: ## Stop the stack
	$(COMPOSE) down

.PHONY: logs
logs: ## Tail logs (svc=<name> to filter)
	$(COMPOSE) logs -f $(svc)

.PHONY: ps
ps: ## List running services
	$(COMPOSE) ps

.PHONY: build
build: ## Build all images
	$(COMPOSE) build

.PHONY: smoke
smoke: ## Run end-to-end smoke test
	bash scripts/smoke.sh

.PHONY: clean
clean: ## Remove containers, volumes, networks
	$(COMPOSE) down -v --remove-orphans

.PHONY: test-auth test-accounts test-transactions test-notifications test-gateway
test-auth:           ; cd services/auth-service && ./mvnw -q test
test-accounts:       ; cd services/accounts-service && ./mvnw -q test
test-transactions:   ; cd services/transactions-service && go test ./...
test-notifications:  ; cd services/notifications-service && pytest -q
test-gateway:        ; cd services/api-gateway && npm test --silent
