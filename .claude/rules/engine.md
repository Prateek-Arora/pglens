---
description: Conventions for the analysis engine (:engine)
paths:
  - "engine/**"
---
- **Pure / I/O split is load-bearing.** Packages `model`, `parse`, `detect`, `candidate`, `rank`,
  `hygiene` carry **no Spring imports** (Jackson is allowed) so they unit-test with no container and
  Phase 2 reuses them server-side. Only `db` (and the `PgLensEngine` facade that wires it) may use
  spring-jdbc. The pure half must never import `db`.
- **The hygiene safety invariant lives in the pure analyzer.** `IndexHygieneAnalyzer` must never emit
  a finding for a `IndexInfo.guarded()` index (unique / PK / FK / constraint-backing). `CatalogReader`
  computes that guard as one boolean at the edge (`constraint_backed` folds in FK-column coverage);
  the analyzer only trusts `guarded()`. "Unused" comes from a persisted scan **window**, never a
  single `idx_scan` read — a backwards delta is a reset, so it's inconclusive, not "unused".
- **One connection per scan.** HypoPG hypothetical indexes are session-local, so the whole
  scan runs on a single `SingleConnectionDataSource` (`DataSources.forScan`). Don't open a second
  connection in the scan path.
- **Read-only is enforced at the DB.** `DataSources.applySessionGuards` issues
  `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` (+ statement/lock timeouts) on that one
  connection; the target rejects any write. Never rely on a framework `readOnly` flag, and never
  add a code path that writes to the monitored DB.
- **HypoPG cleanliness.** The validator resets after **every** candidate; assert
  `SELECT count(*) FROM hypopg() = 0` after a run. Nothing is ever built on the real database.
- **No fabricated numbers.** Every cost is a generic-plan **planner estimate** and labeled as such;
  GIN/GiST (and a missing hypopg) → `NOT_PLANNER_VALIDATED` with no delta, never a guessed number.
- **Phase 2.5 evidence is edge-only and value-free** (ADR-0038). For a validated index the validator
  adds an `IndexFootprint` (HypoPG size vs heap) and a `ValueRangeEstimate`: each equality `$N` in
  the plan (`EqualityParameterLocator.all`) is re-planned with `pg_stats` values from `ValueSampler`
  (quoted by the **server** via `quote_literal`, never in Java). The sampled **values never leave the
  validator** — only frequencies/drops go into models, JSON, or the wire. Never vary a range /
  expression / `ANY` predicate. Rank with `RankingScore.drop` (floor when a range exists) — the
  server uses the same function; don't duplicate the formula.
- **Rules are liberal; HypoPG gates.** A `Rule` flags a pattern from plan + `CatalogSnapshot`; it
  never pre-judges cost. Column extraction is regex over EXPLAIN VERBOSE's qualified text
  (`PlanColumns`) — add operators there, longest-first in the alternation.
- **Tests:** fast unit tests are `./gradlew :engine:test` (no Docker); Testcontainers tests are
  `@Tag("it")` and run via `./gradlew :engine:integrationTest` (needs Docker + the
  `pglens/monitored-db:0.0.0` image). Assert on plan shape / used-or-not / threshold — **never**
  wall-clock. Style is google-java-format via Spotless (`spotlessApply`); don't hand-format.
  Run the same suite on another major with `docker build --build-arg PG_MAJOR=18 -t
  pglens/monitored-db:pg18 deploy/compose/monitored` + `-PmonitoredImage=pglens/monitored-db:pg18`
  (CI's `compat` job does 17 + 18).
- **Tag PgLens's own queries with `DataSources.introspection(sql)`**, never by prepending the
  marker: PG18's pg_stat_statements strips a *leading* comment, so the marker must sit after the
  first keyword or PgLens ranks itself (ADR-0036).
- **PG16+ only.** `StatsReader` refuses an older server up front (`GENERIC_PLAN` is PG16+); don't add
  a literal-substitution fallback without a new ADR.
