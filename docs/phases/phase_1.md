# Phase 1 — Core Advisor Engine (CLI)

**Status:** 🟡 code-complete, awaiting `v0.0.1` tag (steps 1–12 done, 2026-08-26) · **Ships:** `pglens scan <conn>` — the costliest queries, the real plan, and HypoPG-validated `CREATE INDEX` recs (human + `--json`) · **Tag:** `v0.0.1` (on user go-ahead)
**Prereqs:** Phase 0 (`v0.0.0-scaffold`) — the two-instance compose env + the seeded, reliably-slow demo DB + `demo/slow_queries.sql` oracle. Plan verified via `../workflows/plan-verification.md`; full plan at `.claude/plans/prancy-spinning-panda.md`.

---

## 1. Context & prerequisites (self-contained)

Phase 1 is the heart of PgLens: point it at a Postgres (read-only) and get an **honest** report — the
costliest statements from `pg_stat_statements`, *why* each is slow (from the real plan), and, where
applicable, a **HypoPG-validated** `CREATE INDEX` with a real planner-cost delta labeled as an
estimate. No agent/gRPC (Phase 2), no LLM (Phase 3), no UI (Phase 4). It is built as **two clean
halves** — a pure-analysis half (no DB) and a DB-I/O half — so Phase 2 reuses the pure half
server-side and moves the I/O half to the collector.

## 2. Verification of the user's plan (what was checked / corrected)

- **`EXPLAIN (GENERIC_PLAN)` confirmed** (PG16, off by default, mutually exclusive with ANALYZE) — the
  safe way to plan `pg_stat_statements`' normalized `$1` text without inventing literals. Costs use
  default selectivity → every number labeled a **generic-plan planner estimate**.
- **HypoPG ceiling confirmed:** simulates btree/brin/hash/bloom (+ partial `WHERE`); **cannot** do
  GIN/GiST → those route to a *not planner-validated* bucket (ADR-0016, ADR-0019).
- **Spike corrections that shaped the build:** (a) pgjdbc's extended protocol treats the literal
  `$1` in `EXPLAIN (GENERIC_PLAN) <text>` as a bind param and fails → connect with
  `preferQueryMode=simple`; (b) the list SRF in hypopg 1.4.3 is `hypopg()`, not
  `hypopg_list_indexes()`; (c) *partial* indexes aren't generic-plan-validatable (the generic `$1`
  can't satisfy the partial predicate) → offer the full-column btree as the primary rec.
- **`track=all` noise resolved** by filtering `toplevel = true` + current dbid + utility/self (ADR-0014).
- **Gap closed:** the demo pack had no GIN fixture → added **#11** `events.payload @> '{"ua":"agent-7"}'`.

## 3. What was built (realized layout)

```
:engine (java-library, toolchain JDK 21)
  com.pglens.engine           PgLensEngine (facade: one read-only connection, wires the pipeline), PgLensException
  com.pglens.engine.model     PURE records — PlanNode, StatementStat, CatalogSnapshot/TableInfo/IndexInfo,
                              Finding, IndexCandidate, AccessMethod, Recommendation, ValidationResult,
                              RankedRecommendation, ScanReport/QueryReport/PlanSummary/TargetInfo, RankBy, ConnectionTarget
  com.pglens.engine.parse     PURE — PlanParser (EXPLAIN JSON → PlanNode)
  com.pglens.engine.detect    PURE — AntiPatternDetector + Rule strategies R1/R3/R4/R7, PlanColumns, PlanContext
  com.pglens.engine.candidate PURE — IndexCandidateGenerator (Findings → CREATE INDEX; composite merge; AM-tagged)
  com.pglens.engine.rank      PURE — Recommender (cross-query ranking + btree-prefix dedupe)
  com.pglens.engine.db        I/O  — DataSources (read-only guards), StatsReader, CatalogReader,
                              PlanCapturer, HypoPGValidator
:cli (Spring Boot bootJar)
  com.pglens.cli              PglensCommand + scan/explain (picocli via a Spring IFactory), ScanReportRenderer
```

**Pipeline** (`PgLensEngine.scan`): `StatsReader` (rank + hygiene) → `PlanCapturer` (GENERIC_PLAN,
VERBOSE, JSON) → `PlanParser` → `AntiPatternDetector` → `IndexCandidateGenerator` →
`HypoPGValidator` (the decisive gate) → `Recommender` (cross-query top list). `explain(queryid)`
reuses the same per-query `analyze()` step.

**Rules shipped:** **R1** selective unindexed seq-scan filter · **R3** unindexed equi-join key (PK
side not flagged) · **R4** Sort feeding a Limit · **R7** containment/existence filter (jsonb `@>`,
`?`/`?|`/`?&`, `@?`/`@@`) → **GIN** candidate. The composite index is a *candidate* that emerges by
merging an R1 filter with an R4 sort (ADR-0015). R5 (row mis-estimation, ANALYZE-gated) and R6
(over-fetch advisory) are intentionally inactive.

**Honesty & safety, enforced not asserted:** read-only is set at the DB
(`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`) on the single scan connection, so any write
is rejected (ADR-0020); HypoPG runs on that one connection and resets after every candidate
(`hypopg() = 0` after a run); every cost is a labeled planner estimate; GIN/GiST/no-hypopg →
`NOT_PLANNER_VALIDATED` with no number. The `--json` v1.0 contract **is** the pure model record
graph serialized `NON_NULL`, so it can't drift from the code (ADR-0018).

**Decisions:** ADR-0012 (Spring Boot CLI + engine split) · 0013 (GENERIC_PLAN; ANALYZE deferred) ·
0014 (ranker hygiene) · 0015 (pure detection; R1/R3/R4) · 0016 (HypoPG gate; GIN labeling) · 0017
(cross-query ranking + dedupe) · 0018 (`--json` contract + orchestration) · 0019 (R7 GIN rule) ·
0020 (session read-only enforcement + E2E + CI).

## 4. Deliberately skipped (YAGNI) + resources

- **Skipped:** real `EXPLAIN ANALYZE` / literal synthesis (read-only already rejects any write, so the
  statement-tag SELECT-only guard ships with that later feature); native image; a DB-level read-only
  **role** (Phase 2); LIKE `'%…%'` trigram GIN (needs `pg_trgm` + `gin_trgm_ops`, no fixture);
  GraphQL/REST/agent/LLM/UI.
- **Resources (fast path):** *Use The Index, Luke!* (concatenated index, "why isn't my index used",
  ordering) · PG docs → Using EXPLAIN + GENERIC_PLAN · HypoPG README (create → EXPLAIN → reset).

## 5. Definition of Done

- [x] `pglens scan <conn>` prints top-N slow queries ranked by total time (human + `--json`).
- [x] Every `demo/slow_queries.sql` anti-pattern is detected — per-rule unit tests over real fixtures,
      and **#3** (typed-literal range) captured + R1-detected + HypoPG-validated `btree(created_at)`
      −50.7% after the ADR-0021 fix (was silently skipped; found in the end-to-end re-verification).
- [x] btree/… recs are HypoPG-confirmed used + above threshold; ignored candidates suppressed
      (negatives #8 negligible-win, #10 index-not-used verified live + in the validator IT).
- [x] GIN fixture (#11) surfaced as *not planner-validated* — not dropped, not mislabeled, no delta.
- [x] No unlabeled numbers; real pgss mean-time shown as the measured "before".
- [x] Safety proven: read-only session (write rejected), zero hypothetical indexes left behind — E2E test.
- [x] `--json` schema documented as the Phase 2 contract (ADR-0018). Unit + Testcontainers tests green in CI.
- [ ] Tag `v0.0.1` + README "Try it on your DB in 2 minutes" — **pending user go-ahead**.

## 6. Verification (how it was proven)

- **82 tests green** — 61 unit (pure rules/candidates/ranker/renderers + the typed-literal rewrite,
  over real captured EXPLAIN fixtures) + 21 Testcontainers integration (StatsReader hygiene incl.
  self-introspection, CatalogReader, PlanCapturer, HypoPGValidator used/suppressed/threshold/GIN, and
  a full-engine **E2E** driving `scan()`/`explain()` incl. the typed-literal range query).
- **CI:** a `build` job (setup JDK 21 → build the monitored image → `./gradlew build` = Spotless +
  unit + integration) runs beside the existing lint + compose-smoke jobs.
- **Proven live** against the demo DB (`postgresql://pglens:pglens@localhost:5433/pglens_demo`): real
  HypoPG deltas (e.g. `orders(customer_id)` −98.7% on the selective query), honest suppression
  (products.category −0.3%, orders(customer_id) not used on the country-join, `orders(status)`
  unchanged), the `interval $1` statement correctly skipped, #11 GIN rendered `[not
  planner-validated]` and excluded from the validated top list, honest weighting, valid `--json`,
  exit codes (2 usage / 1 runtime / 0 ok), and safety (`hypopg() = 0`, zero real indexes created).
- Found + fixed a real bug live: R4 evidence printed raw `%s`/`%.2f` (a `.formatted()` precedence
  slip) — now guarded by a regression assertion.

## 7. Durable learnings (→ Claude memory)

See memory: HypoPG session-local; GENERIC_PLAN + HypoPG gotchas (partial not generic-validatable,
`hypopg()` list fn, generic overstates skewed wins, 15% threshold); pgjdbc `preferQueryMode=simple`;
dev-machine env (JDK 17 host / toolchain 21, Docker daemon, psql-in-container).
