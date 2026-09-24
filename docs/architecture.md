# PgLens — Architecture

> End-state architecture and the **deliberate design choices** behind it. Read on demand. Update this file whenever structure changes. The *why* behind each choice also lives in `docs/decisions.md` (linked by ADR id).

_Last updated: 2026-09-24 — end-state target below; Phases 0–2.5 are implemented (see the "implemented" sections). Supported Postgres: 16+ (ADR-0036)._

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
3. **HypoPG for validation** (ADR-0005). Suggesting an index is easy; checking it helps without building it (minutes on a big table) is the valuable part. HypoPG gives the real planner's cost *estimate* with the index — the honest "expected improvement", always labeled an estimate. Covers btree/brin/hash/bloom + partial; GIN/GiST are surfaced but labeled *not planner-validated*. **Not a differentiator on its own** — Dexter, PoWA, Supabase `index_advisor` and Postgres MCP Pro use HypoPG too (ADR-0037); PgLens's edge is the integrated, history-aware loop on pgss + hypopg only.
4. **Local LLM (Ollama) for privacy** (ADR-0006). Query text never leaves the user's infra — the sharpest differentiator vs hosted AI-EXPLAIN tools.
5. **gRPC for ingest + edge-pull validation** (ADR-0001 stack; topology ADR-0023, lib ADR-0025). Stats flow agent → server as short-lived **client-streaming** calls; validation work is **server-streamed** to the agent and results **client-streamed** back — three of gRPC's four modes, chosen over live-bidi so calls stay short-lived and load-balanceable. A real, justified gRPC use case (not bolted on).

## Recommendation accuracy (implemented — Phase 2.5, ADR-0038)
The engine's *whether* (HypoPG used + ≥ 15 % gate) was already trustworthy; Phase 2.5 fixes the *how
much* and the *which set*, reusing the same edge-validation path:
- **Value range.** For a validated index, the validator finds every equality predicate on a bound
  `$N` in the query's plan and re-plans the query with real `pg_stats` values (top-3 MCVs + a typical
  histogram value) substituted for that `$N`, without and with the hypothetical index. It reports the
  worst and best drop beside the generic one, and ranking uses the **worst case as a floor**
  (`RankingScore`, shared by CLI + server). Sampled values stay at the edge; only frequencies and drops
  travel. On the demo this moved `orders(customer_id)` from −98.7 % to its hot-customer −56.7 % and
  dropped a join index the planner ignores for the hot customer from #1 to last.
- **Footprint + write load.** HypoPG's size estimate vs the table heap (a per-write cost proxy), and a
  tuple-level write-load note from `pg_stat_user_tables` (server: a persisted `table_stats` window; CLI:
  cumulative since the database's stats reset). A note, never a suppression.
- **Overlap.** When a validated index is a btree prefix of another, the wider one is HypoPG-validated
  against the narrower one's query (`CoverageChecks`); only a pass makes the narrower redundant.
  `AdviceService` groups recs per index for the Phase-4 API.
- **External benchmark.** `make accuracy` builds a TPC-H-derived workload (pinned `tpch-kit`), runs
  PgLens as `pglens_ro`, then builds every recommended index on the throwaway copy and measures it
  (`docs/benchmarks.md`).
- Contracts: `--json` **1.1** (additive), proto `ValidateResult`/`TableStat` optional fields, Flyway
  **V6**.

## Collector agent, server & history — gRPC (implemented — Phase 2)
Phase 1's one-shot CLI became a running two-process system, topology **`a-pull`** (ADR-0023). Two new
Gradle modules recompose the Phase-1 engine's two halves across the wire — **no engine rewrite**:

- **`:agent`** (Spring Boot daemon) reuses only the engine **I/O half** and is **stateless**: a
  `@Scheduled` sampler reads cumulative `pg_stat_statements` + a catalog snapshot as the least-privilege
  **`pglens_ro`** role (ADR-0030), and streams a `SampleBatch` to the server over **client-streaming**
  gRPC (plain grpc-java, ADR-0025) with a bearer token (ADR-0027). Text + generic plan are registered
  **once per queryid**; it never computes a delta (a sampled interval mean would be a fabricated number).
- **`:server`** (Spring Boot) reuses only the engine **pure half** and owns all state — it has **no
  connection to any monitored DB**. It computes **ack-anchored per-interval deltas** server-side (reset
  detected via the global `pg_stat_statements_info.stats_reset`, ADR-0024) and persists a **delta
  time-series** to the metadata DB (Flyway, ADR-0026). A **singleton `@Scheduled` analysis** (guarded by
  a transaction-scoped advisory lock, ADR-0028) runs the pure `detect → candidate` pipeline over the
  **persisted** catalog and enqueues candidate DDLs into a work queue.
- **Validation is pulled to the edge:** the agent leases jobs (**server-streaming** `LeaseValidations`,
  `FOR UPDATE SKIP LOCKED`), runs `HypoPGValidator` next to the DB, and **reports** verdicts back
  (**client-streaming**) — **no bidi** (a deliberate, load-balanceable trade). The server persists
  ranked `recommendations` with real HypoPG cost estimates (a not-validated candidate stores NULL
  costs, never a fabricated 0.0).
- **Also shipped:** **index-hygiene** advice (unused/duplicate/redundant, honest only over the snapshot
  window; never a guarded PK/FK/unique index — ADR-0029); **trend / top-mover / new-slow** queries over
  the series (null-not-fabricated derived numbers — ADR-0031); **Docker images + compose** for the whole
  loop (non-root, copy-prebuilt jars — ADR-0032).

**`:proto`** is the single `.proto` contract (Ingest/Validation/Health) both apps depend on. The
**metadata schema** (`monitored_dbs`, `query_texts`, `query_cumulative`, `query_stats`, catalog +
hygiene + `recommendations` + `validation_jobs`) is forward-only Flyway **V1–V5**. PgLens **dogfooded
its own** time-series index tuning: the deliberately-withheld `query_stats` trend index was measured and
added as **`BRIN(captured_at)`** (V4 — ~73× fewer buffers on the cross-query top-movers scan; ADR-0033,
`docs/benchmarks.md`). The validation queue self-heals a stuck lease via a fence-less reclaim +
dead-letter (V5 `attempts` column; ADR-0035). Ships **`v0.0.2`** (+ a v0.0.3 hardening patch).

## Analysis engine (implemented — Phase 1)
The CLI engine is built as **two clean halves** in a Gradle `:engine` library (+ a `:cli` Spring Boot
bootJar), toolchain-pinned to JDK 21:
- **Pure half** (`model` / `parse` / `detect` / `candidate` / `rank`) — **no Spring imports** (Jackson
  only), so it unit-tests with no container and Phase 2 reuses it server-side unchanged.
- **I/O half** (`db`) — spring-jdbc: `DataSources`, `StatsReader`, `CatalogReader`, `PlanCapturer`,
  `HypoPGValidator`. The `PgLensEngine` facade wires the pipeline over **one** connection.

Pipeline: `StatsReader` (rank + hygiene — including dropping PgLens's own `/*pglens:introspection*/`
queries) → `PlanCapturer` (`EXPLAIN (GENERIC_PLAN, VERBOSE, JSON)`, rewriting `TYPE $N` typed-literal
remnants to `$N::TYPE` so normalized text still parses) → `PlanParser` → `AntiPatternDetector`
(R1/R3/R4/R7; R1 covers range/expression predicates) → `IndexCandidateGenerator` → `HypoPGValidator`
(the decisive used-+-threshold gate; GIN/GiST/no-hypopg → labeled *not planner-validated*) →
`Recommender` (cross-query ranking + btree-prefix dedupe). Output: a human report and a `--json`
v1.0 contract that **is** the pure model record graph (can't drift). Rationale: ADR-0012…0021.

**Safe-by-default is enforced, not assumed:** the whole scan runs on a single connection (HypoPG is
session-local) set **read-only at the database** (`SET SESSION CHARACTERISTICS AS TRANSACTION READ
ONLY`) with statement/lock timeouts — writes are rejected by Postgres, driver-independently
(ADR-0020). HypoPG resets after every candidate (`hypopg() = 0` after a run); nothing is ever built
on the monitored DB. Phase 2 added the second, independent layer: the agent logs in as a DB-level
least-privilege **`pglens_ro`** role with no write grant (ADR-0030) — defense in depth.

## Dev environment (implemented — Phase 0)
Docker Compose stands up the two Postgres instances (`deploy/compose/`): `monitored-db`
(custom image = `postgres:${PG_MAJOR}-bookworm` + `postgresql-${PG_MAJOR}-hypopg`, `PG_MAJOR` default 16, 17/18 in CI `compat`; `pg_stat_statements`
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
- Index-rec *magnitude* and *set* quality → generic-plan deltas overstate skewed wins, and selection was per-query with no write-overhead model. Phase 2.5 (ADR-0038) added value-range floors, footprint/write-load notes and prefix-overlap cross-validation; a full workload solver is still backlog B16, and `pg_stat_statements` never tells PgLens which parameter values a workload really uses (only a range can be reported).
- Postgres-version drift → PG16+ gate + CI `compat` on 17/18 (ADR-0036). PG18 already changed pgss behaviour (leading comments stripped), so new majors get tested, not assumed.
- LLM hallucination → deterministic core owns all facts; validate suggested DDL parses and matches the analyzer's rec.
