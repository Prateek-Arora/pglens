# PgLens — Phase 0 task runner. `make help` lists targets.
# One-command quickstart:  make up && make seed && make warmup && make test

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
DB_USER ?= pglens
MON_DB  ?= pglens_demo
META_DB ?= pglens_meta

.DEFAULT_GOAL := help
.PHONY: help up seed reseed warmup test smoke down clean logs ps psql-monitored psql-metadata lint

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n",$$1,$$2}'

up: ## Build images and start both databases, waiting for health
	@docker info >/dev/null 2>&1 || { echo "Docker daemon not running — start Docker Desktop"; exit 1; }
	$(COMPOSE) up -d --build --wait

seed: ## Load demo data (idempotent; skips if already seeded)
	$(COMPOSE) exec -T monitored-db psql -v ON_ERROR_STOP=1 -q -U $(DB_USER) -d $(MON_DB) < demo/seed.sql

reseed: ## Wipe and reload demo data
	$(COMPOSE) exec -T monitored-db psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(MON_DB) \
		-c "TRUNCATE customers, products, orders, order_items, events RESTART IDENTITY CASCADE;"
	$(MAKE) seed

warmup: ## Replay slow queries so pg_stat_statements accumulates stats
	bash demo/warmup.sh

test smoke: ## Run the smoke test (reproducibility gate + Phase 1 oracle)
	bash scripts/smoke_test.sh

lint: ## Lint shell, Dockerfile, and SQL (skips linters that aren't installed)
	bash scripts/lint.sh

ps: ## Show container status
	$(COMPOSE) ps

logs: ## Follow container logs
	$(COMPOSE) logs -f

psql-monitored: ## Open a psql shell on the monitored database
	$(COMPOSE) exec monitored-db psql -U $(DB_USER) -d $(MON_DB)

psql-metadata: ## Open a psql shell on the metadata database
	$(COMPOSE) exec metadata-db psql -U $(DB_USER) -d $(META_DB)

down: ## Stop containers (keeps data volumes)
	$(COMPOSE) down

clean: ## Stop containers AND delete data volumes
	$(COMPOSE) down -v
