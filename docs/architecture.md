# PgLens — Architecture

> End-state architecture and the **deliberate design choices** behind it. Read on demand. Update this file whenever structure changes. The *why* behind each choice also lives in `docs/decisions.md` (linked by ADR id).

_Last updated: 2026-09-26 — end-state target below; Phases 0–3 and 4A are implemented (see the "implemented" sections). Supported Postgres: 16+ (ADR-0036)._

## System diagram (end state)

```
   ┌──────────────────────┐     gRPC over TLS (client/server    ┌───────────────────────────────┐
   │  Monitored Postgres  │  ◀── read-only ── pglens-agent  ──▶ │  pglens-server (Spring Boot)  │
   │                      │         streaming, bearer token)    │                               │
   │  (customer's DB)     │     pg_stat_statements, EXPLAIN     │  - rank + plan capture        │
   │  + pg_stat_statements│                                     │  - anti-pattern detector      │
   │  + hypopg (optional) │                                     │  - HypoPG index validator     │
   └──────────────────────┘                                     │  - REST API (login, OpenAPI)  │
                                                                 └───────┬───────────────┬───────┘
                                                                         │ calls          │ SQL
                                                                         ▼                ▼
                                                          ┌───────────────────┐  ┌────────────────────────┐
                                                          │ local LLM (any    │  │ Metadata Postgres      │
                                                          │ OpenAI-compatible │◀─│  + pgvector            │
                                                          │ runtime; Ollama)  │  │  snapshots, recs,      │
                                                          └───────────────────┘  │  docs embeddings,      │
                                                            ▲ :explain module    │  explanations, trends  │
                                                            │ (in CLI + server)  └────────────────────────┘
                                                                         ▲
   Scripts (API token) ── REST ──────────────────────────────────────────┤
   Browser ── Next.js (BFF, calls the API server-side) ── REST ──────────┘
   Deploy: Docker Compose (dev) → Helm on kind/k3d → Terraform to free-tier cloud + Neon
   Observability: PgLens instruments itself (Prometheus/OTel, health checks)
```

## Components
| Component | Tech | Responsibility |
|---|---|---|
| `pglens-agent` (collector) | Java 21 / Spring Boot | Read `pg_stat_statements` + run safe `EXPLAIN` on the monitored DB (read-only); stream over gRPC. |
| `pglens-server` | Spring Boot 4.1 | Persist the history, detect anti-patterns, queue HypoPG validation for the agent, serve the authenticated REST API (OpenAPI). |
| `:explain` (LLM layer, in-JVM) | `java.net.http` → any OpenAI-compatible LLM (default: local Ollama + `qwen3.5:4b`); pgvector docs retrieval on the server | Turn PgLens's facts into a checked plain-language explanation. Optional; falls back to a deterministic template (ADR-0043). |
| Metadata Postgres | Postgres + pgvector | PgLens's own store: snapshots, recommendations, embeddings, trends. |
| Dashboard | Next.js (App Router) | Leaderboard, plan viewer, recommendations, trends over time. |

## Deliberate design choices (each ties to the "usable" bar)
1. **Two Postgres instances, never one** (ADR-0003). The *monitored* DB is touched read-only. PgLens keeps its own *metadata* Postgres with pgvector. Bonus: gives us a real growing schema to dogfood indexing on.
2. **Deterministic core, LLM as a layer** (ADR-0004). The analyzer is correct and explainable with zero LLM; the LLM only translates to prose. Trustworthy + useful offline.
3. **HypoPG for validation** (ADR-0005). Suggesting an index is easy; checking it helps without building it (minutes on a big table) is the valuable part. HypoPG gives the real planner's cost *estimate* with the index — the honest "expected improvement", always labeled an estimate. Covers btree/brin/hash/bloom + partial; GIN/GiST are surfaced but labeled *not planner-validated*. **Not a differentiator on its own** — Dexter, PoWA, Supabase `index_advisor` and Postgres MCP Pro use HypoPG too (ADR-0037); PgLens's edge is the integrated, history-aware loop on pgss + hypopg only.
4. **Local LLM for privacy** (ADR-0006, refined by ADR-0043). Query text never leaves the user's infra by default: any OpenAI-compatible runtime works (Ollama is the documented default), and non-local endpoints are refused unless explicitly allowed.
5. **gRPC for ingest + edge-pull validation** (ADR-0001 stack; topology ADR-0023, lib ADR-0025). Stats flow agent → server as short-lived **client-streaming** calls; validation work is **server-streamed** to the agent and results **client-streamed** back — three of gRPC's four modes, chosen over live-bidi so calls stay short-lived and load-balanceable. A real, justified gRPC use case (not bolted on).

## Web API & security (implemented — Phase 4A, ADR-0044, ADR-0045)
Phase 4A made the server usable without SQL, and safe to expose. **Spring Boot 4.1** first (3.5 left
OSS support on 2026-06-30).

- **gRPC over TLS by default.** A compose one-shot `certs` service makes a dev CA + server
  certificate (CA key discarded; the agent's mount holds only `ca.pem`). Real deployments bring their
  own certificate; plaintext is an explicit, logged opt-in on both sides.
- **HTTP API, one auth choke point** (like the gRPC `AuthInterceptor`, ADR-0027): a stateless
  `BearerTokenFilter` resolves opaque tokens — sessions (`pglens_s_`, 8 h idle / 7 d max) and named
  read-only API tokens (`pglens_a_`) — to a user; `SecurityConfig` holds every rule (ADMIN / VIEWER;
  API tokens never write). Only SHA-256 token hashes and bcrypt password hashes are stored (V9
  `users`, `sessions`, `api_tokens`). Login throttling is in-memory per username (single server
  instance). `pglens.auth.mode=none` is for one person on localhost. Errors are RFC 9457.
- **Registration is an API** (B10): `POST /api/v1/databases` returns the agent token once; rotate
  and delete (name confirmation; deletes only PgLens's history). `make register` wraps it.
- **Read API** (`/api/v1`, OpenAPI via springdoc): leaderboard, query detail (estimated plan tree
  whose node ids match each finding's `planNode`, `--json` 1.4), trend, explanations (template on
  read — no LLM needed — or a cached LLM answer for the same facts hash), recommendations with the
  confirm caveat, hygiene, top movers, new slow queries, cross-database recommendations. `queryid` is
  a string everywhere; field names say measured vs estimated.
- **Hourly rollup** (V10, ADR-0045): `query_stats_hourly`, kept exact by a statement-level trigger on
  the append-only `query_stats`, serves every windowed read; windows start on a UTC hour. Measured
  on a 30-day, 500-query history: 30-day leaderboard 741 → 44 ms p95 (`make bench-api`).
- **Backend-for-frontend (4B):** the browser will only talk to Next.js, whose server code calls this
  API on the private network — no CORS, and the API authorizes every request itself.

Metadata schema is now Flyway **V1–V10** (V6 evidence + `table_stats`, V7 build caution, V8
explanations + pgvector knowledge, V9 users/sessions/API tokens + `last_ingest_at`, V10 rollup).

## Plain-language explanations (implemented — Phase 3, ADR-0043)

```
engine model (QueryReport, Recommendation)       — CLI: from the scan; server: rebuilt from query_texts,
        │                                          query_cumulative, recommendations + the pure detector
        ▼ FactsBuilder (pure)
ExplanationFacts ─► TemplateExplainer (pure, always available) ─────────────────┐ fallback
        │   cards by rule id (knowledge/cards/*.md)                              │
        │   [server] + pgvector docs passages (links only unless the A/B says)   │
        ▼                                                                        │
PromptBuilder ─► OpenAiCompatibleClient ─► OutputGuard ─ pass ─► Explanation ◄───┘
   (EndpointPolicy: local only by default)     │ fail → 1 retry with the violations → template
                                               ▼
CLI ExplanationRenderer: prose + PgLens-rendered DDL, estimate label, cautions, provenance, links
Server ExplanationService: scheduled, off by default → `explanations` cache (facts hash + model + prompt)
```

- **Module boundary.** `:explain` depends on `:engine`'s model; `:engine` never depends on `:explain`,
  so "deterministic core" is enforced by the build graph. `:cli` and `:server` both use it.
- **What the model sees and writes.** Pre-formatted facts (query, measured calls/times, the index,
  the planner estimate, only the findings this index addresses) plus rule cards. It writes three
  prose fields. DDL, labels and every caveat stay out of the prompt and are rendered by PgLens.
- **Guard.** Shape; no SQL; every number unit- and magnitude-aware within 5 % of a fact; every
  `snake_case`/dotted/backticked name known; only the recommended index mentioned; cost drop called a
  cost; no "N× faster", promises, or percentages for an unvalidated index.
- **Runtime realities handled.** `reasoning_effort: none` (thinking models); `json_schema` →
  `json_object` → no `reasoning_effort` downgrade on HTTP 400; `finish_reason=length` / empty /
  unparseable → template with a reason; a down endpoint short-circuits the rest of the run.
- **Server.** `ExplanationService` runs on its own schedule (so a slow model never delays analysis),
  explains the top N actionable indexes per db, caches by `(db, queryid, ddl, facts_hash, model,
  prompt_version)`. An outage is cached as a template with `retry_after`; a rejected answer is
  cached for good (temperature 0 repeats). V8 adds `knowledge_chunks` (`vector(768)`, HNSW cosine)
  and `explanations`.
- **Eval.** 24 frozen real cases, pre-registered decision rules, dev/held-out split:
  `docs/llm-eval.md`.

## Recommendation accuracy (implemented — Phase 2.5, ADR-0038; amended by ADR-0041)
The engine's *whether* (HypoPG used + ≥ 15 % gate) was already trustworthy; Phase 2.5 fixes the *how
much* and the *which set*, reusing the same edge-validation path:
- **Value range.** For a validated index, the validator finds every equality predicate on a bound
  `$N` in the query's plan and re-plans the query with real `pg_stats` values (top-3 MCVs + a typical
  histogram value) substituted for that `$N`, without and with the hypothetical index. It reports the
  worst and best drop beside the generic one, with a caution when the worst case is below the gate.
  Sampled values stay at the edge; only frequencies and drops travel. **Ranking uses the generic drop**
  (`RankingScore`, shared by CLI + server): v0.0.4 ranked by the worst case, but the pre-registered
  JOB/IMDB benchmark found that floor further from reality on 6 of the 8 recs where it differed —
  it comes from a column's hottest value, and `pg_stat_statements` can't say whether the workload
  queries it (ADR-0040/0041). The range is evidence beside the score, not the score.
- **Build caution (B17).** HypoPG never writes an index entry, so it can't see that a B-tree key value
  is too wide (> ~2.7 kB) to index. For a validated B-tree the validator reads the catalog only — key
  column types and the table's TOAST size — and cautions when an unbounded column sits on a table that
  stores long values out of line (`BtreeEntryWidth`), with a one-line check query.
- **Footprint + write load.** HypoPG's size estimate vs the table heap (a per-write cost proxy), and a
  tuple-level write-load note from `pg_stat_user_tables` (server: a persisted `table_stats` window; CLI:
  cumulative since the database's stats reset). A note, never a suppression.
- **Overlap.** When a validated index is a btree prefix of another, the wider one is HypoPG-validated
  against the narrower one's query (`CoverageChecks`); only a pass makes the narrower redundant.
  `AdviceService` groups recs per index for the Phase-4 API.
- **External benchmarks.** `make accuracy` (TPC-H-derived, pinned `tpch-kit`) and `make
  accuracy-job` (the Join Order Benchmark on the real IMDB snapshot, pre-registered) run PgLens as
  `pglens_ro`, then build every recommended index on a throwaway copy and time the workload's own
  statements under the server's settings (`docs/benchmarks.md`). JOB showed the limit of any
  estimate: 18 % of validated recs made their query slower, which is why every report says
  "planner-validated ≠ safe" and why Phase 2.6 confirms indexes on a user's copy (below).
- Contracts: `--json` **1.2** (1.1 + `buildCaution`, both additive), proto `ValidateResult` /
  `TableStat` optional fields, Flyway **V6** + **V7**.

## Confirm on a copy (implemented — Phase 2.6, ADR-0042)
`pglens confirm` turns the accuracy benchmark's method into a user command: **build each recommended
index for real on a scratch copy and time the workload's own statements before and after.** It is a
CLI-only path; it never connects to the scanned database, and the agent/server are unchanged.

```
scan.json ──► ConfirmPlan (top N distinct indexes + their queries' normalizedText, estimates)
.sql / PG log ──► StatementSource (reads only; $N + logged literal values)
                         │
copy DB ◄── CopyTarget.open: marker in pg_db_role_setting · not the scanned host:port/db ·
                             primary · PG16+ · pg_stat_statements · tables owned
        ◄── CopyMeasurer:  match   EXPLAIN (VERBOSE) → copy queryid → run one per shape (READ ONLY,
                                   rolled back) → copy pgss text (top-level) == report text
                           baseline EXPLAIN (ANALYZE, TIMING OFF), warm-up + median of 3
                           per index CREATE INDEX pglens_confirm_<n> → re-time → DROP (finally)
                         │
                   Outcome/Verdict (pure) ──► ConfirmReport (confirm JSON 1.0) ──► CLI
```

- **Pure half** (`engine/confirm`): statement parsing (`.sql` splitting that respects quotes,
  dollar-quotes and comments; PostgreSQL stderr logs in both logging modes), text matching,
  plan-from-report, verdict arithmetic, the report model. **I/O half** (`engine/db`): `CopyTarget`
  (the guard; the only writable connection in PgLens) and `CopyMeasurer`.
- **Why text, not queryid:** queryid hashes relation OIDs and jumbles literals and bound parameters
  differently, so the same statement has another queryid on a restored copy. The copy's own
  pg_stat_statements normalizes the replayed statement exactly as production's did, so the texts
  match. `$N` statements are replayed via `PREPARE`/`EXECUTE` (substituting the values renumbers the
  constants).
- **Privacy:** statement values stay in memory; the report carries queryids, counts and times, and a
  failure only its SQLSTATE.

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
- Index-rec *magnitude* and *set* quality → generic-plan deltas overstate skewed wins, and selection was per-query with no write-overhead model. Phase 2.5 (ADR-0038) added value ranges (shown as evidence; ranking stays generic per ADR-0041), footprint/write-load notes and prefix-overlap cross-validation; JOB/IMDB (ADR-0040) showed estimates can't rule out slowdowns — Phase 2.6 `pglens confirm` measures on a copy (ADR-0042), and every API recommendation carries that command; a full workload solver is still backlog B16, and `pg_stat_statements` never tells PgLens which parameter values a workload really uses (only a range can be reported).
- Postgres-version drift → PG16+ gate + CI `compat` on 17/18 (ADR-0036). PG18 already changed pgss behaviour (leading comments stripped), so new majors get tested, not assumed.
- LLM hallucination → the model never writes DDL, numbers-with-meaning or caveats of its own: PgLens renders those, and `OutputGuard` checks every number, name and cost/runtime phrasing in the model's three sentences (retry once, then template). What it can't check — a wrong number-free sentence — is measured in `docs/llm-eval.md` (M4) and disclosed in every explanation's provenance line.
- API exposure → login required by default, API tokens read-only, compose binds the HTTP port to loopback, HTTPS via a reverse proxy (README). The login throttle and session touch are per server instance (in-memory throttle) — fine for one server; several replicas would need a shared throttle.
