# PgLens · Phase 2.5 — Recommendation Accuracy Sprint

> **Status: ✅ SHIPPED 2026-09-24 as `v0.0.4`.** Written from the 2026-09-24 independent plan review
> (ADR-0037), then plan-verified the same day against the real demo DB (§7 — **it changed Step A**;
> design recorded in ADR-0038).
>
> **Self-contained.** Prereqs: Phase 1 engine (`v0.0.1`) + Phase 2 agent/server/history (`v0.0.2`,
> hardening `v0.0.3`). **Tag at end:** `v0.0.4`.

## 1. Context — why this phase exists, and why before Phase 3

PgLens's core is sound: a candidate index survives only if the planner actually **uses** the HypoPG
hypothetical index **and** its cost drops ≥ 15 % (ADR-0016). That makes the *whether* ("this index
helps") trustworthy. The 2026-09-24 review found three weaknesses in the *how much* and the *which set*:

1. **Magnitude error from generic plans.** Costs come from `EXPLAIN (GENERIC_PLAN)`, which plans with
   default/average selectivity. The Phase-1 spike measured the gap on the demo DB: `orders(customer_id)`
   **−98.7 % generic vs ~−57 % real** (hot customer), `order_items(order_id)` **−97 % generic vs ~−13 %
   real**. The cross-query ranking score (ADR-0017: real total time × relative drop) inherits that
   error, so the order of the "fix these first" list can be wrong.
2. **Per-query selection, no write cost.** Each query gets its own best index; nothing weighs the
   write overhead an index adds or whether one composite could serve several queries. Mature peers do:
   pganalyze's Indexing Engine models *Index Write Overhead* and picks a workload-wide set with a
   CP-SAT solver; Postgres MCP Pro runs a DTA-style budgeted search. Per-query advice drifts towards
   index sprawl (index hygiene only cleans up *afterwards*).
3. **Self-authored oracle.** Every accuracy test runs on `demo/slow_queries.sql` — queries written to
   be caught. That proves the pipeline works, not how accurate it is on a workload we didn't design.

Phase 3's LLM **only phrases facts the engine owns** (charter #3). If the engine's numbers are off, the
prose makes them more persuasive, not more correct — so accuracy comes first.

## 2. Goal & non-goals

**Goal:** every validated recommendation carries a second, more realistic planner estimate; the
ranking uses the best estimate available; write-heavy tables and multi-query overlap are surfaced; and
PgLens publishes a **measured** precision / estimate-error result on a workload it did not author.

**Non-goals (deliberately skipped — YAGNI):** a CP-SAT / constraint solver for index-set selection
(backlog B16); a `pg_qualstats` dependency (needs `shared_preload_libraries` on the target and is
missing on several managed services — keep PgLens at *pgss + hypopg only*); `auto_explain`
ingestion (B6a); PG ≤ 15 support (declined, ADR-0036); any write to the monitored DB.

## 3. Plan (ordered)

### Step A — Value-range estimates (the magnitude fix) → backlog B14 · *corrected by verification, §7*
- For a **planner-validated** candidate, find **every equality predicate on a bound parameter** in the
  query's baseline plan (`alias.col = $N`, any table — up to 3 parameters), read each column's
  `pg_stats`, and sample **the 3 most common values + the histogram's median bound** (a typical
  non-MCV value). Every value is quoted **server-side** (`quote_literal`), never concatenated in Java.
  *(Built first for the index's own leading column only; the live demo showed a join index —
  `order_items(order_id)` — whose fetched rows are decided by `orders.customer_id = $1`, so all of a
  query's equality parameters are varied, one at a time, the rest left generic.)*
- For each sampled value, substitute it for that one `$N` (other parameters stay `$N`, still under
  `EXPLAIN (GENERIC_PLAN)`), and plan the query without and with the same hypothetical index.
- Report a **range**: the **worst case** (lowest relative drop) with the column and its value's
  frequency, and the **best case**. Keep the generic figure beside it. **Sampled values never leave the
  edge** — only frequencies and drops are reported, sent, or stored (they are real data).
- **Ranking (ADR-0017) uses the worst case as a conservative floor** when a range exists
  ("at least X ms, even if every call hit the heaviest common value"), else the generic figure; the
  report names the basis. The ≥ 15 % gate (ADR-0016) is unchanged; if the floor is under 15 % the
  rec carries a "depends on which values you query" caution.
- Range / `LIKE` / expression / `ANY(ARRAY…)` predicates are never varied — never a guessed value.

### Step B — Write-overhead and overlap awareness (a lighter step before a full workload model) → backlog B13
- The agent adds per-table cumulative `n_tup_ins/upd/del` + `seq_scan/idx_scan` to the catalog it
  already ships (proto `TableStat` extension + a migration), so the server can compute a **write rate
  over the persisted window** (same honest window logic as index hygiene — a backwards counter is
  a reset, so the result is inconclusive).
- For each validated recommendation, attach `hypopg_relation_size()` of the hypothetical index
  (HypoPG's size estimate) and its **ratio to the table's heap size** — a per-write maintenance proxy
  equivalent in spirit to pganalyze's *entry size / row size* (both scale with row count), taken from
  HypoPG's own estimate instead of a second model. Labeled *estimate*.
- **Flag, don't suppress:** a table that wrote more tuples (`n_tup_ins+upd+del`) than it read
  (`seq_tup_read + idx_tup_fetch`) over the window is **write-dominant** and its recs get a "weigh the
  maintenance cost" note (same unit on both sides — tuples). The server uses the persisted window; the
  one-shot CLI uses the cumulative counters "since the database's stats reset" and says so.
- **Overlap consolidation (prefix pairs):** when a validated rec is subsumed by a more general one
  (its btree key is a prefix of another rec's, same table — already flagged by the Recommender), run
  the general index against the subsumed rec's **query** through HypoPG. Only if it passes that query's
  gate is it reported as covering both ("one index instead of two"); per-query estimates for the same
  index may then be summed, because each was validated against that exact index. The CLI does this in
  its session; the server enqueues it as an ordinary `(queryid, ddl)` validation job. Non-prefix
  merges are skipped (YAGNI).

### Step C — External-workload benchmark (the honest accuracy number) → backlog B15
- Load a public workload PgLens did not author into a throwaway PG16 container, **with only primary
  keys**: a **TPC-H-derived** workload at SF 0.1 — data + query instances from `dbgen`/`qgen`
  (`gregrahn/tpch-kit`, pinned commit `852ad0a`, built in a throwaway `gcc:13` container at bench
  time, not vendored). Labeled "TPC-H-derived; not an official TPC result".
- Run the query set N times to populate `pg_stat_statements`, run PgLens, then for **each** recommended
  index: build it on the throwaway copy, `ANALYZE`, and measure real `EXPLAIN (ANALYZE, BUFFERS)`
  (median of 5, parallelism + JIT off — the ADR-0033 method; buffers = the cache-independent metric).
- Publish in `docs/benchmarks.md`: **precision** (share of recs that really reduced buffers/time by
  ≥ the gate), **estimate error** (generic vs representative vs measured, per rec), and misses we
  noticed. (Dexter comparison: skipped — needs a Ruby toolchain for context only; revisit at launch.)
- A reproducible harness (`scripts/accuracy_benchmark.sh`, `make accuracy`), like `make bench`.

### Step D — Close-out
- ADR(s) for any decision made in A–C; update `docs/architecture.md`, `.claude/rules/engine.md`,
  README (the estimate labels), `docs/project.md`. Tag `v0.0.4`.

## 4. Definition of Done
- [x] Validated recs carry a value-range estimate (worst → best across sampled values of the query's
      equality parameters), labeled distinctly from the generic one; queries without an equality
      parameter stay generic-only (tested both ways). — `ValueRangeIntegrationTest`, `ValueRangesTest`.
- [x] Ranking uses the worst-case floor when present and says which basis; on the demo DB the
      `orders(customer_id)` floor is the hot-customer estimate (**−56.7 %**, live CLI run), not the
      generic −98.7 %. — `RankingScore`, `RecommenderTest`, `ValidationFlowIntegrationTest`.
- [x] Every `pg_stats` value reaches SQL through server-side `quote_literal` (tested with a hostile
      value); the session stays read-only; `hypopg() = 0` after a run. Sampled values never leave the
      edge (asserted on the proto descriptor).
- [x] Recs show estimated index size + write-overhead ratio; write-dominant tables are flagged from a
      window of real counters (reset = inconclusive, tested). — `IngestFlowIntegrationTest`, `WriteLoadTest`.
- [x] A subsumed rec's query is HypoPG-checked against the subsuming index; "covers both" is shown
      only when it passes. — CLI in-session; server via `CoverageChecks` jobs + `AdviceService`.
- [x] `docs/benchmarks.md` has a reproducible external-workload result: coverage 20/21, precision
      11/14 (79 %) by warm time, per-rec estimate vs measured — **measured numbers only**, labeled.
      *(Superseded 2026-09-25: re-measured under the server's own settings, 6/14 (43 %) — ADR-0041.)*
- [x] Tests green: PG16 full build **207/0**; PG17 + PG18 integration **71/71** each (CI `compat` job
      added — not yet run on GitHub).
- [x] Commit + tag `v0.0.4` — branch `phase-2.5/v0.0.4-accuracy` → PR → merged, tagged.
- [ ] Teach-back (ADR-0037): the user can explain value ranges vs weighted means, why the floor ranks,
      and why warm time (not buffers) judged the benchmark — without notes.

## 5. Verification (self-check)
Pick the rec PgLens ranks #1 on the external workload and build it for real. If the measured win
isn't in the same ballpark as the representative estimate, the estimate work isn't done — say so in
`docs/benchmarks.md` rather than tuning the number.

**Result (2026-09-24):** #1 = `lineitem(l_shipdate)` for TPC-H Q20 — estimated −73.8 %, measured
−76.0 % warm time. #2 = `lineitem(l_partkey)` for Q17 — −90.0 % vs −99.5 %. The misses (3 of 14) and
the metric change (buffers → warm time, made after run 1) are published in `docs/benchmarks.md`
(ADR-0039).

## 6. Risks
- **Parameter-to-column mapping is heuristic** (regex over VERBOSE plan text). Mitigation: only the
  simple `col <op> $N` shape; everything else keeps the generic label.
- **More EXPLAINs on the monitored DB.** Planning only (no execution), bounded by a per-candidate
  sample size (≤ 5 values) and the existing statement timeout.
- **The benchmark may make PgLens look worse.** That's the point: publish it anyway (charter #1).

## 7. Plan verification (2026-09-24, ADR-0008) — what changed

**Facts verified on the real demo DB, as `pglens_ro` in a read-only session:** `pg_stats` is readable
for the monitored tables (MCVs cast via `most_common_vals::text::text[]`); `EXPLAIN (GENERIC_PLAN)`
accepts a statement with one parameter replaced by a literal and the rest left as `$N`;
`hypopg_relation_size(indexrelid)` works (orders(customer_id): 4536 kB estimated vs an 11 MB heap);
`pg_stat_user_tables` write/read counters are readable; `quote_literal` quotes MCV text. The
read-only session also rejects even temp scratch objects — so all of this runs as pure planning.

**The finding that changed Step A.** For demo query #1 (`orders WHERE customer_id = $1`), HypoPG
relative cost drops were:

| Estimate | Drop |
|---|---|
| Generic plan (today) | −98.7 % |
| Value `'1'` (hottest customer, 1.8 % of rows — the value the demo workload queries) | −56.7 % |
| Values `'2'` / `'3'` / `'6'` | −60.7 % / −64.6 % / −69.1 % |
| Weighted by the column's value distribution (the original Step A) | −96.2 % |

A distribution-weighted single number barely moves off the generic one, because 88 % of rows belong
to non-MCV customers where the index wins ~99 %. The workload's *actual* parameter values are what
matter, and `pg_stat_statements` doesn't keep them. So the original DoD ("materially closer to
~57 %") was unreachable by that method. **Corrected design:** report the **range** across sampled
values and rank by its **worst-case floor** — which, for equality predicates, is the heaviest common
value. On the demo that floor is −56.7 %, matching the real workload. The weighted mean is dropped
(it hides exactly the skew the user needs to see).

**Found while building (live demo):** varying only the index's own leading column missed join indexes.
`order_items(order_id)` (demo #4) ranked **#1** at a generic −58.3 %, but its fetched rows are decided
by `orders.customer_id = $1`; varying that parameter shows the planner **doesn't use the index at all
for the hot customer** (−0.0 %), so it now ranks last with a caution — which matches the demo
workload (it only queries the hot customer). Hence: vary all of a query's equality parameters.

**Other verified adjustments:** equality predicates only (a range predicate's worst case is always
"select everything", which is uninformative); the write-overhead proxy reuses HypoPG's size estimate;
overlap consolidation limited to prefix pairs; TPC-H-derived data via a pinned `tpch-kit` build
(verified: builds in `gcc:13`, SF 0.1 = ~107 MB of `.tbl` files, `qgen` emits concrete instances; its
trailing `limit -1;` lines must be stripped). JSON contract bumps to **1.1** (additive fields only).

