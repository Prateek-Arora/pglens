# Phase 2 — Collector Agent + Server + History (gRPC)

**Status:** ✅ shipped — PR #3 merged to `main` (`3898649`), tagged **`v0.0.2`** (2026-08-30), CI green · **Ships:** a running `pglens-agent` that streams `pg_stat_statements` to a `pglens-server` over gRPC, which persists a **time-series**, runs the Phase-1 engine on a schedule with **edge-HypoPG-validated** recs, produces **index-hygiene** advice, and answers **trend / top-mover** queries — both apps as Docker images in compose.
**Prereqs:** Phase 1 (`v0.0.1`) — the two-clean-halves `:engine` (pure `parse/detect/candidate/rank` + I/O `db`) whose seam this phase recomposes across the wire, and the seeded, reliably-slow demo DB. Plan verified via `../workflows/plan-verification.md`; full plan at `.claude/plans/federated-inventing-quilt.md`.

---

## 1. Context & prerequisites (self-contained)

Phase 1 was a one-shot CLI over a **single** monitored-DB connection. Phase 2 is the real product
shape: a thin, stateless **`pglens-agent`** next to each monitored DB streams cumulative
`pg_stat_statements` + a catalog snapshot over **gRPC** to a central **`pglens-server`** that persists
**time-series snapshots** to its own metadata Postgres, computes per-interval deltas, runs the Phase-1
engine on a schedule, validates recommendations, produces index-hygiene advice, and answers
trend/top-mover questions. No LLM (Phase 3), no dashboard (Phase 4). The engine's **pure/IO seam maps
straight onto the split** — the `db` half moves to the agent, the pure half to the server — so this is
a **recomposition, not an engine rewrite**.

## 2. Verification of the user's plan (what was checked / corrected)

- **Topology researched & chosen: `a-pull`** (ADR-0023) over the plan's implied live-bidi. Edge-validation
  privacy does **not** require bidi; short-lived, load-balanceable calls avoid LB idle-timeout babysitting,
  don't pin a stream to one server instance, and decouple ingest from validation latency. Teaches three of
  gRPC's four modes (unary + client- + server-streaming); **bidi consciously not built** (a recorded trade).
- **gRPC library corrected twice → plain grpc-java** (ADR-0025). The plan's `net.devh` starter is
  unmaintained; the *official* Spring gRPC was the plan-verification pick — but its maintained 1.0.x line
  requires Boot **4.1**, incompatible with the pinned Boot 3.5.16, so the realized decision is **plain
  grpc-java** (same reasoning as ADR-0012's plain-picocli call).
- **Reset detection corrected: global `pg_stat_statements_info.stats_reset`, not per-entry `stats_since`**
  (ADR-0024). The plan assumed PG16 pgss exposes `stats_since`; the monitored image's pgss is **1.10**, where
  `stats_since` is **PG17-only**. Deltas are computed **server-side, ack-anchored** to the last *persisted*
  cumulative — which also auto-satisfies the "no lost window on a failed send" self-check with no disk buffer.
- **`.proto` needs a `CatalogSnapshot`** the plan's message list omitted — server-side detection reasons over
  row estimates + existing indexes, read at the edge. Text + plan register **once per queryid**; per-interval
  samples carry only `queryid` + counters.
- **Two pre-committed deliverables folded in:** **index-hygiene** (B5 — owned by Phase 2 because "unused" is
  only honest over the snapshot window, ADR-0029) and the **DB-level read-only role** (closes ADR-0020's
  deferred item, ADR-0030).
- **Scaling down-payment:** the scheduled analysis is a **singleton** via a transaction-scoped advisory lock
  (ADR-0028) — no new dependency, safe under N server replicas; full HPA stays a Phase-5 concern (stated as a
  known risk, not claimed as real horizontal scaling).

## 3. What was built (realized layout)

```
:proto (protobuf + grpc-java codegen)     pglens/v1/pglens.proto — Ingest / Validation / Health; the single contract
:agent (Spring Boot bootJar → app.jar)    reuses :engine db half only
  com.pglens.agent                        SampleCollector (@Scheduled sampler), ValidationRunner (@Scheduled edge loop)
  com.pglens.agent.grpc                   IngestClient (client-streaming), ValidationClient (lease + report), token metadata
  com.pglens.agent.map                    ProtoMappers, TextHash — pure, unit-tested
:server (Spring Boot bootJar → app.jar)   reuses :engine pure half only; NO monitored-DB connection
  com.pglens.server.ingest                IngestService (StreamSnapshot), DeltaCalculator (pure), AuthInterceptor (token)
  com.pglens.server.analysis              AnalysisService (advisory-lock singleton), IndexHygiene wiring
  com.pglens.server.validation            LeaseValidations (server-streaming), ReportValidations (client-streaming)
  com.pglens.server.trend                 TrendService + TrendMath (pure null-not-fabricated), Trend DTOs
  com.pglens.server.persistence           JDBC repositories; Flyway migrations V1–V4
:engine  (+2 pkgs)                        engine.hygiene (IndexHygieneAnalyzer — pure); db.StatsReader.globalStatsReset, CatalogReader hygiene cols
```

**Runtime pipeline (a-pull):** agent `SampleCollector` reads cumulative pgss + catalog as **`pglens_ro`**
→ **client-streams** `SampleBatch` → server `DeltaCalculator` (ack-anchored delta, reset via global
`stats_reset`) persists the `query_stats` time-series + replaces the catalog. Server `AnalysisService`
(advisory-lock singleton) runs the pure `PlanParser → AntiPatternDetector → IndexCandidateGenerator` over
the **persisted** catalog → enqueues `validation_jobs`. Agent leases (server-streaming, `FOR UPDATE SKIP
LOCKED`) → `HypoPGValidator.validateDdl` at the **edge** → client-streams verdicts → server persists ranked
`recommendations`. `IndexHygieneAnalyzer` runs in the same pass; `TrendService` answers series/top-movers/new-slow.

**Metadata schema (forward-only Flyway):** V1 (`monitored_dbs`, `query_texts`, `query_cumulative`,
`query_stats`, `index_catalog`/`index_stats`, `recommendations`, `validation_jobs` + partial queue indexes),
V2 (`table_catalog` + detection columns), V3 (`index_hygiene`), **V4 (`BRIN(captured_at)` — the dogfooded
trend index, ADR-0033)**.

**Honesty & safety, enforced not asserted:** the agent never computes a delta (a sampled interval mean would
be fabricated); a not-validated candidate stores **NULL** costs, never `0.0`; trend percent-change is `null`
with no prior baseline and a window mean is `null` for zero calls (pure `TrendMath`); hygiene never flags a
unique/PK/FK/constraint index; **two independent read-only layers** (the ADR-0020 session guard *and* the
`pglens_ro` role's missing write grant) each reject a write; HypoPG resets to `hypopg() = 0` after each edge
candidate.

**Decisions:** ADR-0023 (a-pull topology) · 0024 (server-side ack-anchored deltas; global-reset detection) ·
0025 (plain grpc-java + `:proto` codegen) · 0026 (metadata schema + Flyway) · 0027 (per-agent token auth;
mTLS deferred) · 0028 (advisory-lock singleton analysis) · 0029 (index hygiene graduates in) · 0030 (read-only
role) · 0031 (trend queries, null-not-fabricated) · 0032 (Docker packaging + `make register`) · 0033 (dogfood
→ `BRIN(captured_at)`).

## 4. Deliberately skipped (YAGNI) + resources

- **Skipped:** **bidirectional streaming** (chosen against; documented trade) · **mTLS/OIDC** (token only;
  Phase 6) · **disk-spool buffering** (ack-anchored deltas make it unnecessary; backlog if ever offline) ·
  **full REST/GraphQL API** (Phase 4 — only the trend service seam the IT drives is built) · **multi-replica
  HPA infra** (Phase 5 — single server + advisory-lock singleton ships) · a **registration/admin API**
  (`make register` SQL bootstrap for now — backlog **B10**) · **`btree(db_id, captured_at)`** (measured, only
  wins for a many-tenant store — backlog **B11**) · **Kafka/Redis** (out of scope — an interval collector
  needs no broker).
- **Resources (fast path):** grpc-java docs (unary + client-/server-streaming; skip bidi) · Flyway Getting
  Started (note `flyway-database-postgresql` is a separate artifact since Flyway 10) · PG16 pg_stat_statements
  docs (reset/eviction semantics) · PG BRIN docs + *Use The Index, Luke!* (why BRIN fits append-only
  `captured_at`).

## 5. Definition of Done

- [x] `pglens-agent` streams samples to `pglens-server` over gRPC on an interval; both ship as Docker images in compose.
- [x] Deltas correct across intervals incl. after `pg_stat_statements_reset()` (tested — post-reset `calls_delta` stays correct, not negative).
- [x] Server persists snapshots; a trend query returns a query's mean-exec-time over time + a top-movers list.
- [x] Server runs the Phase-1 engine on schedule and stores recs with HypoPG-validated evidence via the `Validation` RPC round-trip (edge HypoPG).
- [x] **Index-hygiene rec** produced (unused + duplicate) over the snapshot window, with safe guard rails.
- [x] DB-level read-only role (`pglens_ro`) enforced for the agent — write rejected independently of the session guard.
- [x] Unknown/unauthenticated agents rejected (token → `UNAUTHENTICATED`).
- [x] **Dogfood result recorded in `docs/benchmarks.md`** with real, labeled before/after numbers → `BRIN(captured_at)` (V4).
- [x] Agent survives server downtime (ack-anchored deltas) without losing a window (§10 self-check).
- [x] `.proto` documented; Testcontainers integration test (agent+server+DB) green; CI extended to the new modules.
- [x] ADRs 0023–0033 recorded; `project.md` / `architecture.md` / this `phase_2.md` / `.claude/rules/` updated.
- [x] Tag **`v0.0.2`** — Phase 2 committed (`a019248`) → PR #3 → merged to `main` (`3898649`) → annotated tag pushed. **Phase 2 shipped.**

## 6. Verification (how it was proven)

- **153 tests green, 0 failures** — 97 unit + 56 Testcontainers integration: engine 73 unit / 29 IT (hygiene
  analyzer, CatalogReader, HypoPG, read-only role), server 11 unit / 27 IT (delta/trend math + ingest, analysis,
  validation round-trip, hygiene flow, Flyway incl. the V4 BRIN check, and the full **agent→server→metadata flow
  IT** driving the *real* agent runtime), agent 10 unit, cli 3 unit.
- **Post-Step-12 independent audit (ADR-0034).** An end-to-end re-verification confirmed no charter/safety/
  correctness violations, and fixed three gaps it found: **F1** — re-analysis re-enqueued an already-DONE
  candidate every tick (unbounded `validation_jobs` + edge HypoPG re-run every 30s) → a revalidate cooldown +
  terminal-job retention; **F2** — idle queries wrote zero-`calls_delta` rows → `DeltaCalculator` now emits none;
  **F3** — the "§10 no-lost-window" item had been conflated with the reset test → added a direct dropped-batch
  ingest test. **F4** (a crashed-mid-lease job stays `LEASED`) is deferred to backlog **B12** with a researched,
  chosen lease-reclaim + dead-letter design (it's a schema+tuning+fencing feature, not an inline patch).
- **CI:** the `build` job builds every module via root `./gradlew build` (Spotless + unit + integration, incl.
  the flow IT) and now also `docker build`s the agent + server images (Dockerfile/COPY regression guard); the
  `lint` job runs the single-source-of-truth `scripts/lint.sh` (shellcheck incl. the dogfood script, hadolint on
  all three Dockerfiles, sqlfluff); the compose-smoke job was scoped to the data-plane dbs.
- **Proven live in compose** (`make up` + `make register` + `make seed` + `make warmup`): the agent logs in as
  `pglens_ro`, streams samples; the metadata DB accumulates the `query_stats` time-series, validation jobs, and
  **`PLANNER_VALIDATED` recommendations with real HypoPG cost estimates** (e.g. 6442.7 → 8.6, a labeled
  generic-plan drop); agent logs clean, no auth errors.
- **Dogfood benchmark** (`make bench`): 2.1 M-row `query_stats` backfill; the top-movers scan rode the V1 PK at
  **~162,961 buffers**, `BRIN(captured_at)` cut it to **~2,238 (~73×)** for a 24 kB index → V4. Real numbers,
  honestly labeled (`docs/benchmarks.md`).

## 7. Durable learnings (→ Claude memory)

See memory: HypoPG session-local; the pgss reset correction (PG16 = 1.10, `stats_since` is PG17 → use the global
`stats_reset`); Spring JDBC wraps PG "permission denied" as `BadSqlGrammarException` (assert on the stack trace);
disabling a Spring Boot module's plain `jar` empties its runtime variant (breaks `project(...)` deps — name the
bootJar `app.jar` instead); dogfood-benchmark methodology (buffers-not-walltime on a cache-fitting box, a
single-db PK skews the plan, BRIN for append-only time columns, bump the Flyway-count IT when adding a migration).
