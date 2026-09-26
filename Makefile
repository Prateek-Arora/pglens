# PgLens — Phase 0 task runner. `make help` lists targets.
# One-command quickstart:  make up && make seed && make warmup && make test

COMPOSE := docker compose -f deploy/compose/docker-compose.yml
DB_USER ?= pglens
MON_DB  ?= pglens_demo
META_DB ?= pglens_meta

.DEFAULT_GOAL := help
.PHONY: help up seed reseed warmup register test smoke bench bench-api accuracy accuracy-job llm-up llm-down llm-eval down clean logs ps psql-monitored psql-metadata lint secrets hooks

help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-16s\033[0m %s\n",$$1,$$2}'

up: ## Build jars + images and start the full stack (dbs + server + agent), waiting for health
	@docker info >/dev/null 2>&1 || { echo "Docker daemon not running — start Docker Desktop"; exit 1; }
	bash scripts/dev_env.sh
	./gradlew :server:bootJar :agent:bootJar
	$(COMPOSE) up -d --build --wait

seed: ## Load demo data (idempotent; skips if already seeded)
	$(COMPOSE) exec -T monitored-db psql -v ON_ERROR_STOP=1 -q -U $(DB_USER) -d $(MON_DB) < demo/seed.sql

reseed: ## Wipe and reload demo data
	$(COMPOSE) exec -T monitored-db psql -v ON_ERROR_STOP=1 -U $(DB_USER) -d $(MON_DB) \
		-c "TRUNCATE customers, products, orders, order_items, events RESTART IDENTITY CASCADE;"
	$(MAKE) seed

warmup: ## Replay slow queries so pg_stat_statements accumulates stats
	bash demo/warmup.sh

register: ## Register the demo db through the HTTP API (or rotate its token) and restart the agent with it — run after `make up`
	bash scripts/register.sh

test smoke: ## Run the smoke test (reproducibility gate + Phase 1 oracle)
	bash scripts/smoke_test.sh

bench: ## Dogfood benchmark — measure PgLens's own trend query, before/after the time-series index (KEEP=1 keeps the container)
	bash scripts/dogfood_benchmark.sh

bench-api: ## API latency benchmark — every read endpoint over HTTP on a 30-day, 500-query history (KEEP=1 keeps the containers)
	bash scripts/api_benchmark.sh

accuracy: ## Accuracy benchmark — PgLens's recs vs measured reality on a TPC-H-derived workload (SF=0.1; KEEP=1 keeps the container)
	bash scripts/accuracy_benchmark.sh

accuracy-job: ## Accuracy benchmark on real skewed data — JOB queries on the IMDB snapshot (1.3 GB download; hours)
	WORKLOAD=job bash scripts/accuracy_benchmark.sh

llm-up: ## Start the optional local LLM (Ollama, CPU in Docker) and pull its models (~3.7 GB, once)
	$(COMPOSE) --profile llm up -d --wait llm
	$(COMPOSE) --profile llm run --rm llm-pull

llm-down: ## Stop the local LLM (keeps the downloaded models)
	$(COMPOSE) --profile llm stop llm

llm-eval: ## Run the Phase 3 explanation eval against a running LLM (SPLIT=dev|heldout|all, MODEL=…, DOCS=true)
	./gradlew :explain:llmEval -Psplit=$(or $(SPLIT),dev) $(if $(MODEL),-Pmodel=$(MODEL)) $(if $(DOCS),-Pdocs=$(DOCS))

secrets: ## Scan the whole git history for secrets (gitleaks; uses Docker if it isn't installed)
	bash scripts/secret_scan.sh

hooks: ## Enable the repo's git hooks (pre-commit: secret scan of staged changes + AGENTS.md guard)
	git config core.hooksPath scripts/git-hooks
	@echo "git hooks enabled: scripts/git-hooks"

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
