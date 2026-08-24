# PgLens — Architecture

> End-state architecture and the **deliberate design choices** behind it. Read on demand. Update this file whenever structure changes. The *why* behind each choice also lives in `docs/decisions.md` (linked by ADR id).

_Last updated: 2026-08-22 — pre-implementation; this is the target, not yet built._

## System diagram (end state)

```
   ┌──────────────────────┐        gRPC (server-streaming)      ┌───────────────────────────────┐
   │  Monitored Postgres  │  ◀── read-only ── pglens-agent  ──▶ │  pglens-server (Spring Boot)  │
   │  (customer's DB)     │     pg_stat_statements, EXPLAIN     │  - rank + plan capture        │
   │  + pg_stat_statements│                                     │  - anti-pattern detector      │
   │  + hypopg (optional) │                                     │  - HypoPG index validator     │
   └──────────────────────┘                                     │  - REST + (opt) GraphQL API   │
                                                                 └───────┬───────────────┬───────┘
                                                                         │ calls          │ SQL
                                                                         ▼                ▼
                                                          ┌───────────────────┐  ┌────────────────────────┐
                                                          │ explain-svc (LLM) │  │ Metadata Postgres      │
                                                          │ RAG over PG docs  │◀─│  + pgvector            │
                                                          │ Ollama (local)    │  │  snapshots, recs,      │
                                                          └───────────────────┘  │  embeddings, trends    │
                                                                                  └────────────────────────┘
                                                                         ▲
   Next.js dashboard (App Router) ── REST/GraphQL ─────────────────────┘
   Deploy: Docker Compose (dev) → Helm on kind/k3d → Terraform to free-tier cloud + Neon
   Observability: PgLens instruments itself (Prometheus/OTel, health checks)
```

## Components
| Component | Tech | Responsibility |
|---|---|---|
| `pglens-agent` (collector) | Java 21 / Spring Boot | Read `pg_stat_statements` + run safe `EXPLAIN` on the monitored DB (read-only); stream over gRPC. |
| `pglens-server` | Spring Boot | Rank slow queries, capture plans, detect anti-patterns, run HypoPG validation, expose REST/GraphQL. |
| `explain-svc` (LLM layer) | Ollama + RAG (pgvector) | Turn structured analysis into plain-language explanation + rationale. Optional; degrades gracefully. |
| Metadata Postgres | Postgres + pgvector | PgLens's own store: snapshots, recommendations, embeddings, trends. |
| Dashboard | Next.js (App Router) | Leaderboard, plan viewer, recommendations, trends over time. |

## Deliberate design choices (each ties to the "usable" bar)
1. **Two Postgres instances, never one** (ADR-0003). The *monitored* DB is touched read-only. PgLens keeps its own *metadata* Postgres with pgvector. Bonus: gives us a real growing schema to dogfood indexing on.
2. **Deterministic core, LLM as a layer** (ADR-0004). The analyzer is correct and explainable with zero LLM; the LLM only translates to prose. Trustworthy + useful offline.
3. **HypoPG for validation** (ADR-0005). Suggesting an index is easy; proving it helps without building it (minutes on a big table) is the hard, valuable part. HypoPG gives a real planner-cost delta — the honest "expected improvement." Covers btree/brin/hash/bloom + partial; GIN/GiST are surfaced but labeled *not planner-validated*.
4. **Local LLM (Ollama) for privacy** (ADR-0006). Query text never leaves the user's infra — the sharpest differentiator vs hosted AI-EXPLAIN tools.
5. **gRPC server-streaming for ingest** (ADR-0001 stack). Continuous stats flow from agent → server; a real, justified gRPC use case (not bolted on).

## Dev environment (implemented — Phase 0)
Docker Compose stands up the two Postgres instances (`deploy/compose/`): `monitored-db`
(custom image = `postgres:16-bookworm` + `postgresql-16-hypopg`; `pg_stat_statements`
loaded via `shared_preload_libraries`) on host port 5433, and `metadata-db`
(`pgvector/pgvector:0.8.6-pg16`) on 5434. `demo/` holds a reproducible skewed dataset
and `slow_queries.sql` — the documented, HypoPG-validated slow-query oracle later phases
test against. One command: `make up && make seed && make warmup && make test`. Rationale:
ADR-0010, ADR-0011. Java modules (`engine`/`agent`/`server`) and their build tooling land
from Phase 1 on.

## Design patterns & standards applied
- **SOLID / DRY / KISS / YAGNI** throughout. Notable: the analysis engine is built once (Phase 1 CLI), then extracted behind a service interface (Phase 2) — dependency inversion so the CLI, server, and tests share one core.
- **Deterministic-core / optional-adapter** boundary: LLM and dashboard are adapters over a stable analysis API; neither can invent facts.
- **Strategy** for index-type validators (btree/brin/hash/bloom/partial validated via HypoPG; GIN/GiST heuristic + labeled).
- Path-scoped `.claude/rules/*.md` will document module-local conventions as code lands (progressive disclosure — loaded only when those files are touched).

## Skill-coverage map (why this project, for the job search)
| Target skill | Phase(s) | How it's proven |
|---|---|---|
| RDBMS indexing + query tuning (deep) | 1, 2 | Build the tuning engine; tune PgLens's own metadata DB; benchmarked. |
| gRPC + Protobuf | 2 | Collector↔server server-streaming. |
| RAG + Embeddings + pgvector | 3 | RAG over PG docs to ground explanations. |
| LLM feature (shipped, guardrailed) | 3 | Real feature with graceful degradation. |
| Next.js (+ GraphQL optional) | 4 | App-Router dashboard on real data. |
| Kubernetes / Helm | 5 | Helm chart, kind/k3d, config/secrets/HPA (learning demo). |
| Terraform / cloud / observability | 6 | Provision node + Neon; self-instrument. |
| OSS launch / technical writing | 7 | README, benchmarks, demo, release. |

**Deliberately excluded (YAGNI):** Kafka, Cassandra/DynamoDB, ElasticSearch — no need here; don't bolt on.

## Known architectural risks (see charter §9 + `docs/decisions.md`)
- Server horizontal scaling: streaming ingest + singleton `@Scheduled` analysis don't scale trivially → make analysis a leader-elected singleton / separate non-scaled component; Phase 5 HPA is a *learning* demo, not real horizontal scaling of stateful work.
- Index-rec false positives → HypoPG validation gate with a minimum cost-win threshold.
- LLM hallucination → deterministic core owns all facts; validate suggested DDL parses and matches the analyzer's rec.
