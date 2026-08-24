# PgLens

> Open-source, self-hosted Postgres **slow-query & index advisor** with **HypoPG-validated** index recommendations and **local-LLM-explained** query plans.

PgLens watches a Postgres database's `pg_stat_statements`, ranks the queries that actually cost you time, captures their `EXPLAIN` plans, and recommends indexes — then **proves each recommendation against the real planner with [HypoPG](https://github.com/HypoPG/hypopg)** so the "expected improvement" is a measured cost delta, not a guess. A local LLM (via [Ollama](https://ollama.com)) explains each plan in plain language, and a Next.js dashboard shows the leaderboard, plans, recommendations, and trends. Everything runs on free/local infrastructure — **query text never leaves your machine.**

**Status:** 🟢 Phase 0 (Foundations & Dev Environment). This repo currently ships the reproducible dev environment and a deliberately-slow demo database; the analysis engine lands in Phase 1. See the [roadmap](docs/project.md#phase-tracker).

## Design principles

- **No fabricated evidence.** Every number is real and labeled (HypoPG cost *estimate* vs. actual runtime).
- **Safe by default.** The monitored database is touched read-only; recommendations use hypothetical indexes only.
- **Deterministic core, optional AI.** Analysis is correct with the LLM turned off; the LLM only phrases facts the core owns.

## Quickstart

**Prerequisites:** Docker (with the Compose plugin) and GNU Make. Start Docker Desktop first.

```bash
git clone <repo-url> pglens && cd pglens
make up        # build + start the monitored (pg_stat_statements + hypopg) and metadata (pgvector) databases
make seed      # load skewed demo data (~1M rows) with deliberately-missing indexes
make warmup    # replay the slow-query pack so pg_stat_statements accumulates stats
make test      # smoke test: asserts extensions, stats, and a real Seq Scan plan
```

`make help` lists every target. Connect with any client:

| Database | Purpose | Host port | DB name |
|---|---|---|---|
| `monitored-db` | The database PgLens observes (demo data) | `5433` | `pglens_demo` |
| `metadata-db`  | PgLens's own store (pgvector) | `5434` | `pglens_meta` |

Default credentials for local dev are `pglens` / `pglens` (override via `.env`; see `.env.example`).

## What's here now (Phase 0)

- `deploy/compose/` — Docker Compose stack + the monitored-DB image (adds hypopg) + init SQL.
- `demo/` — the demo schema, a reproducible skewed-data generator, and `slow_queries.sql`: a documented pack of known anti-patterns that is the **ground-truth oracle** for Phase 1.
- `scripts/smoke_test.sh` — the end-to-end reproducibility gate, also run in CI.

## Roadmap

Full plan and status live in [`docs/project.md`](docs/project.md); architecture in [`docs/architecture.md`](docs/architecture.md); the *why* behind each decision in [`docs/decisions.md`](docs/decisions.md). Ship target is **Phase 4** (a self-hostable web tool).

## License

[Apache-2.0](LICENSE).
