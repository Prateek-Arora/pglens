---
description: Conventions for the central server (:server)
paths:
  - "server/**"
---
- **Reuses the `:engine` PURE half only** — `parse` / `detect` / `candidate` / `rank` + models. Never
  the `db` I/O half (that runs on the agent, ADR-0023). The server has **no connection to any
  monitored DB**; everything it analyzes comes from persisted state (plans in `query_texts`, catalog
  in `table_catalog`/`index_catalog`). Reconstruct the engine `CatalogSnapshot` from those rows —
  don't try to read a monitored DB.
- **Deltas + reset live server-side** (ADR-0024): the agent sends raw cumulative counters + the global
  `stats_reset`; `DeltaCalculator` (pure, no DB) computes per-interval deltas anchored to the last
  *persisted* snapshot. A **zero-`calls` interval emits no row** (F2/ADR-0034 — an idle query still in
  `pg_stat_statements` would otherwise append a meaningless zero row every interval); the anchor still
  advances via the caller's unconditional cumulative upsert, so anchoring a lost/failed send to the
  last *persisted* snapshot still loses no window (the F3 lost-window IT proves this, distinct from the
  reset test). Keep delta/reset logic unit-testable with no container.
- **Scheduled analysis is a singleton** (ADR-0028): run the whole pass in one transaction guarded by
  **`pg_try_advisory_xact_lock`** (transaction-scoped — auto-released at commit, pinned to the tx
  connection). Never use session-scoped `pg_advisory_lock` here: under the Hikari pool the acquire and
  release can land on different connections. A replica that can't take the lock skips the tick.
- **Catalog is replaced, not upserted.** Each ingest deletes+reinserts this db's `table_catalog` +
  `index_catalog` rows, so a dropped index can't linger and suppress a valid recommendation. The
  catalog is small and slow-changing; correctness beats churn-avoidance.
- **Hygiene findings are derived state, also replaced.** The `idx_scan` counters, by contrast, are a
  **time-series** — `CatalogRepository.recordIndexScans` *appends* them to `index_stats` each ingest
  (idempotent on the natural key); `HygieneRepository.scanWindows` deltas the window. The scheduled
  pass runs the pure `IndexHygieneAnalyzer` and **replaces** this db's `index_hygiene` rows, so a
  fixed index stops appearing. Never persist a hygiene finding for a guarded index — that invariant
  is the analyzer's, not the repository's; the repo just stores what it returns.
- **Enqueue is idempotent AND cooldown-gated** (ADR-0034). The `ON CONFLICT (db_id, queryid,
  candidate_ddl) WHERE state IN ('PENDING','LEASED') DO NOTHING` partial-unique arbiter blocks a
  double-enqueue while a candidate is *in flight*; on top of that, `enqueue` skips a candidate whose
  `recommendations` row is younger than `pglens.analysis.revalidate-after-ms` (default 1h), so a
  DONE job is not instantly re-enqueued every tick (that was audit finding F1 — unbounded
  `validation_jobs` + edge HypoPG re-run every 30s). A rec aged past the cooldown IS re-validated
  (refreshes a stale verdict). The singleton pass also `pruneTerminalJobs` older than
  `job-retention-ms` (default 7d). A crashed-mid-lease job stays LEASED until **B12** (lease-reclaim,
  deferred). Never drop either guard.
- **Migrations are immutable, forward-only Flyway.** Add a new `V<n>__*.sql`; never edit an applied
  one (a comment change is a checksum change — `V1__init.sql`'s dogfood note stays as-is). Correctness/
  queue indexes belong in migrations; the time-series trend index was withheld for the dogfood
  benchmark and is now `BRIN(captured_at)` in **`V4`** (ADR-0033, `docs/benchmarks.md`).
- **Trend derived numbers are NULL-not-fabricated** (ADR-0031). `TrendService`/`TrendRepository`
  answer series / top-movers / new-slow over `query_stats`; every ratio goes through the pure,
  no-Spring `TrendMath` — percent change is `null` when there is no prior baseline (`prior<=0`), a
  window mean is `null` for zero calls. "New" means *first-ever* row in the window (a `NOT EXISTS`
  anti-join), never merely "quiet last window". The cross-query `windowTotals`/`newQueries` scans ride
  the `V4` `BRIN(captured_at)` trend index (ADR-0033); `newQueries` is anti-join-bound (PK-served), so
  the BRIN helps it little — don't add a second index chasing that (`docs/benchmarks.md`).
- **Auth is one choke point** (ADR-0027): the `AuthInterceptor` resolves the `x-pglens-token` header
  to a `MonitoredDb` in the gRPC `Context`; services read the authenticated db from there and never
  trust a `db_name` off the wire. Health is intentionally unauthenticated.
- **No fabricated numbers** (charter #1): every persisted cost is a labeled HypoPG generic-plan
  estimate; the derived per-interval `mean_exec_time` is `total_delta/calls_delta` (NULL when
  `calls_delta=0`), never a sampled lifetime mean.
- **Tests:** unit (no DB) for pure logic; Testcontainers `@Tag("it")` (`:server:integrationTest`,
  pgvector image) for anything touching Postgres, via `@SpringBootTest` + `@DynamicPropertySource`.
  Park the `@Scheduled` analysis in ITs with a huge `pglens.analysis.initial-delay-ms` and drive
  `run()` directly so counts are deterministic. Style is google-java-format via Spotless.
