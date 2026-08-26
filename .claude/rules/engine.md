---
description: Conventions for the analysis engine (:engine)
paths:
  - "engine/**"
---
- **Pure / I/O split is load-bearing.** Packages `model`, `parse`, `detect`, `candidate`, `rank`
  carry **no Spring imports** (Jackson is allowed) so they unit-test with no container and Phase 2
  reuses them server-side. Only `db` (and the `PgLensEngine` facade that wires it) may use
  spring-jdbc. The pure half must never import `db`.
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
- **Rules are liberal; HypoPG gates.** A `Rule` flags a pattern from plan + `CatalogSnapshot`; it
  never pre-judges cost. Column extraction is regex over EXPLAIN VERBOSE's qualified text
  (`PlanColumns`) — add operators there, longest-first in the alternation.
- **Tests:** fast unit tests are `./gradlew :engine:test` (no Docker); Testcontainers tests are
  `@Tag("it")` and run via `./gradlew :engine:integrationTest` (needs Docker + the
  `pglens/monitored-db:0.0.0` image). Assert on plan shape / used-or-not / threshold — **never**
  wall-clock. Style is google-java-format via Spotless (`spotlessApply`); don't hand-format.
