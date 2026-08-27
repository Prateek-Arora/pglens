# PgLens — Deferred Work Backlog

> **What we consciously chose *not* to build yet, and why.** This is the counterpart to
> `docs/phases/` (what we *are* building) and `docs/decisions.md` (the *why* behind what we chose).
> An item lands here when it's a real, worthwhile idea that a phase deliberately skipped — so the
> reasoning isn't lost, and so an open-source contributor can pick it up. Nothing here is a bug or a
> broken promise; it's scoped-out-on-purpose work with an honest rationale.
>
> **For contributors:** items tagged **`good-first-issue`** are self-contained and don't need deep
> context. Items tagged **`needs-design`** touch a core honesty/safety invariant (see
> `CLAUDE.md` → Core principles) and should be discussed before implementation — read the linked ADR
> first. Every item must preserve principle #1 (**no fabricated evidence**) and #2 (**safe-by-default
> vs the monitored DB**).

_Last updated: 2026-08-27._

## How an item is written
**What** (the capability) · **Why deferred** (the honest reason) · **Constraints** (invariants it must
not violate) · **Effort/type** (`good-first-issue` / `needs-design`) · **Refs** (ADR / phase / code).

---

## Analysis engine — more index rules

### B1. BRIN recommendation for correlated columns
- **What:** Recommend a BRIN index (not btree) for a range/inequality filter on a column whose
  physical order tracks its value — e.g. an append-only `created_at`. Detect via
  `pg_stats.correlation` ≈ ±1.0 from the catalog snapshot.
- **Why deferred:** Phase 1 shipped the smallest honest rule set (R1/R3/R4/R7). BRIN is engine polish,
  not on the Phase 2 critical path.
- **Constraints:** **HypoPG *can* validate BRIN**, so this stays fully planner-validated — no honesty
  compromise. Correlation is a heuristic for *which* AM to propose; the cost delta is still HypoPG's.
- **Effort/type:** `good-first-issue` — a new pure `Rule` + a `CatalogReader` column for
  `pg_stats.correlation` + a fixture test. The demo already flags it: `orders(created_at)` is a "BRIN
  candidate: data is date-clustered" (`demo/slow_queries.sql` #3, `10_schema.sql`).
- **Refs:** ADR-0015 (pure-rule pattern), ADR-0016 (HypoPG gate).

### B2. Covering / INCLUDE indexes (index-only scans)
- **What:** When a query selects a few columns beyond its filter/sort, recommend an
  `INCLUDE (…)` index that enables an index-only scan.
- **Why deferred:** Needs the projected-column set threaded from the plan, and INCLUDE-key handling in
  the candidate/validator. Real value, but more plumbing than the Phase 1 rules.
- **Constraints:** HypoPG supports INCLUDE keys (btree), so validatable. Don't over-recommend wide
  INCLUDE lists (write/space cost) — cap and label.
- **Effort/type:** `needs-design` (candidate model + validator changes).
- **Refs:** ADR-0017 (dedupe interacts with covering indexes).

### B3. Expression / functional-index rule (function, cast, and jsonb-field predicates)
- **What:** Recommend an expression index for predicates the current extractor deliberately ignores:
  `lower(email) = $1`, `(customer_id)::text = $1`, `payload ->> 'k' = $1`.
- **Why deferred:** `PlanColumns.predicateColumns` only extracts a *plain* `qualifier.column` before
  the operator — a function/cast wrapper or a jsonb-field extraction is skipped on purpose, because a
  plain btree on the bare column would **not** serve the predicate (recommending one would be a
  candidate HypoPG can't match — a near-miss the gate would suppress, but noisy). The honest fix is a
  distinct expression-index candidate.
- **Constraints:** The DDL must index the *exact* expression the planner sees (`lower(email)`, not
  `email`). HypoPG can validate expression btree indexes, so it stays validated.
- **Effort/type:** `needs-design` — new extraction + candidate shape. Boundaries are already pinned by
  tests in `PlanColumnsTest` (the `doesNotExtract…` cases mark exactly what this item would enable).
- **Refs:** ADR-0015, ADR-0021 (extraction is regex over EXPLAIN VERBOSE text).

### B4. Partial-index recommendation (validation-limited)
- **What:** Recommend a partial index (`… WHERE event_type = 'error'`) for a selective filter on a
  skewed low-cardinality column (demo #6).
- **Why deferred:** **Can't be planner-validated under GENERIC_PLAN** — the generic `$1` can't satisfy
  a concrete partial predicate, so HypoPG won't show the index as used. Offering it unvalidated (or
  validating the full-column btree as a stand-in) is a labeling problem, not a code problem.
- **Constraints:** If surfaced, it must be labeled *not planner-validated* (like GIN), never given a
  fabricated delta. Revisit alongside B7 (real ANALYZE), which *can* validate it.
- **Effort/type:** `needs-design`.
- **Refs:** ADR-0013 (partial not generic-validatable), ADR-0016 (labeling).

---

## Analysis engine — index hygiene (catalog-driven, not plan-driven)

### B5. Unused & duplicate/redundant index detection
- **What:** A different *class* of advice from the query rules: flag indexes that are never scanned
  (`pg_stat_user_indexes.idx_scan`) and indexes that are exact or prefix duplicates of another
  (`pg_index` overlap). High real-world value (wasted disk, slower writes, VACUUM load).
- **Why deferred *from Phase 1*, but NOT backlog-parked:** This is **pre-committed to Phase 2**, not
  open-ended — "unused" is only honest over a *time window*, and Phase 2 is what persists snapshot
  history (`idx_scan` delta between snapshots, not a single point-in-time read). See
  `docs/project.md` → Phase 2 pre-commitments. Listed here for the contributor map; owned by Phase 2.
- **Constraints:** 100% read-only, catalog-only, zero fabrication — the safest advice PgLens gives.
  Never recommend dropping a unique/constraint-backing index or one used by an FK.
- **Effort/type:** `good-first-issue` for the single-snapshot version; the time-window version rides
  Phase 2's history.
- **Refs:** Prior art — PgHero, `pg-index-health`. `docs/project.md` Phase 2 pre-commitments.

---

## Real-execution track (the honest path to real runtime numbers)

> **B6 and B7 are the same feature seen twice** — the only honest way to get *real* runtime (not a
> generic-plan estimate) and the only honest way to get a *real* GIN/GiST cost is to **execute on a
> non-production target**. They should be designed together.

### B6. Opt-in real `EXPLAIN ANALYZE` on a non-prod target
- **What:** An explicitly opt-in mode that runs real `EXPLAIN (ANALYZE, BUFFERS)` to get actual rows,
  actual timing, buffer hotspots, and to enable the row-misestimation rule (R5, currently inactive).
- **Why deferred:** Three hard, honest blockers, none solved by the read/write split people first
  reach for: (1) `pg_stat_statements` stores normalized `$1` text — **there are no literals to run**;
  synthesizing one *fabricates a data distribution* (principle #1). (2) ANALYZE **executes** the
  query, and the leaderboard queries are the expensive ones — running them hits the DB. (3) "It's a
  read" isn't safe from the statement tag alone (a `SELECT` can call a VOLATILE writer; a
  data-modifying CTE has a SELECT tag). GENERIC_PLAN was chosen precisely to avoid all three.
- **Constraints (the honest design):** off by default; target a **replica / staging / restored
  snapshot**, never prod primary; SELECT-only guard + reject data-modifying CTEs (the guard ADR-0020
  deferred); literals **sampled read-only and labeled**, never invented silently.
- **Effort/type:** `needs-design` — touches core safety.
- **Refs:** ADR-0013 (`--explain-analyze` deferred), ADR-0020 (read-only + statement-tag guard),
  ADR-0015 (R5 inactive).

### B6a. `auto_explain` ingestion (real plans from real traffic, zero extra execution)
- **What:** Consume `auto_explain` log output — the **actual** plan of **actual** executions, with
  **real literals** and (optionally) real timing — as a byproduct of normal workload.
- **Why deferred:** Needs config on the monitored DB + log parsing/transport; a natural fit for the
  Phase 2 collector, not the Phase 1 CLI.
- **Constraints:** Sidesteps B6's literal-fabrication and extra-execution problems entirely (the query
  already ran). Query text stays inside the user's infra (principle: privacy).
- **Effort/type:** `needs-design` — pairs with the Phase 2 agent.

### B7. Real GIN/GiST measurement (there is no hypothetical shortcut)
- **What:** Give GIN/GiST recommendations (jsonb `@>`, full-text, LIKE `%…%`) a *real* measured cost
  delta instead of only the *not-planner-validated* label.
- **Why deferred / why it's fundamental:** **No hypothetical-index tool can simulate GIN/GiST.**
  Verified against HypoPG's source (`hypopg_index.c`): it supports **btree/brin/hash/bloom** only and
  rejects everything else with `hypopg: access method "%s" is not supported`. This is not a HypoPG
  gap a different library fills — GIN/GiST cost estimation needs index-internal statistics (entry
  counts, distinct keys, posting-tree shape) that only exist once the index is **built**. So the only
  honest number comes from building it on a **non-prod copy** (= B6's target).
- **Constraints:** Until then, the current behavior is correct and stays: surfaced, labeled *not
  planner-validated*, real `pg_stat_statements` time shown as the measured "before", **no fabricated
  delta**. This is the same posture as peer tools (dexter, POWA/pg_qualstats).
- **Effort/type:** `needs-design` — rides B6's non-prod-execution machinery.
- **Refs:** ADR-0005, ADR-0016, ADR-0019 (GIN labeling); charter principle #6.

### B8. LIKE-trigram GIN recommendation
- **What:** Recommend `USING gin (col gin_trgm_ops)` for `col LIKE '%…%'` predicates.
- **Why deferred:** Needs the non-default `pg_trgm` extension + opclass, and there's no demo fixture —
  adding it in Phase 1 would be untested speculative generality (YAGNI).
- **Constraints:** GIN → *not planner-validated* (same as B7). Detect `pg_trgm` presence before
  suggesting the opclass.
- **Effort/type:** `good-first-issue` (rule + fixture), once a `pg_trgm` demo fixture exists.
- **Refs:** ADR-0019 (explicitly deferred there).

---

## Plan capture

### B9. Type-aware plan capture (recover `= ANY (ARRAY[$1,…])` and cast/function shapes)
- **What:** Capture shapes GENERIC_PLAN currently rejects because normalization strips type info —
  notably `col = ANY (ARRAY[$1, $2, …])` on a **non-text** column (the normalized `$N` default to
  `text`, so the planner sees `integer = ANY(text[])` → "operator does not exist").
- **Why deferred:** The safe fix needs the **column's catalog type** to rewrite the parameters (e.g.
  cast the array), which the pure `PlanCapturer` doesn't have today; a blind structural rewrite risks
  changing the plan shape. The commoner forms already capture (`col IN ($1,…)`, `col = ANY ($1)`), and
  the skip is **surfaced, not silent** — the query still ranks with `planCaptured=false` and the
  renderer labels it "plan not captured … skipped".
- **How we know:** `PlanCaptureRobustnessTest` measures the capture skip-rate over a diverse
  shape-stressor pack; this shape is its one documented `EXPECTED_SKIP`.
- **Constraints:** Any rewrite must not alter the plan's access-method/column shape (that's what
  detection reads). Prefer type-aware casting over structural collapse.
- **Effort/type:** `needs-design`.
- **Refs:** ADR-0021 (typed-literal rewrite — the same family of fix), ADR-0022 (robustness harness),
  `engine/src/test/resources/robustness/shape_stressors.sql`.

---

## Adding to this backlog
When a phase deliberately skips a worthwhile item, add it here (don't bury it in a commit message):
follow the **What / Why deferred / Constraints / Effort / Refs** shape, tag it `good-first-issue` or
`needs-design`, and link the ADR that explains the call. If an item graduates into a phase, move its
"owned by" note (like B5) and leave the entry as the contributor breadcrumb.
