# PgLens benchmarks

Two benchmarks, both reproducible, both **measured** (no fabricated numbers — charter #1):

1. [**Recommendation accuracy on an external workload**](#accuracy-benchmark--tpc-h-derived-workload-phase-25) — `make accuracy` (Phase 2.5, ADR-0038/0039).
2. [**Dogfood: tuning PgLens's own metadata schema**](#pglens-dogfood-benchmark--tuning-our-own-metadata-schema) — `make bench` (Phase 2, ADR-0033).

---

# Accuracy benchmark — TPC-H-derived workload (Phase 2.5)

> **The question:** when PgLens says "create this index", is it right — on a workload **we did not
> write**? Every earlier accuracy test used `demo/slow_queries.sql`, which was authored to be caught.
> Here PgLens runs against a public benchmark schema, then **every index it validates is built for
> real** on a throwaway copy and measured. Run 2026-09-24.

**TPC-H-derived workload — not an official TPC result.** Data and query instances come from
`dbgen`/`qgen` (`gregrahn/tpch-kit` @ `852ad0a`, built at bench time in a throwaway `gcc:13`
container, not vendored).

## Setup
- **Database:** throwaway `pglens/monitored-db:0.0.0` (PostgreSQL **16.15**, hypopg 1.4.x) with
  `pg_stat_statements`. TPC-H schema with **primary keys only** — every secondary index is something
  PgLens must find, or correctly not recommend. **SF 0.1**: lineitem 600,572 rows, orders 150,000,
  partsupp 80,000, part 20,000, customer 15,000, supplier 1,000.
- **Workload:** the 21 TPC-H queries (Q15 excluded — it's `CREATE VIEW` DDL), **3 qgen instances**
  each (different substitution parameters), each run **twice** → pg_stat_statements.
  Two mechanical qgen fix-ups for Postgres (`scripts/accuracy/prepare_queries.py`): the stray
  `limit N;` line is moved into the statement, and `interval '90' day (3)` loses its precision.
- **PgLens:** `pglens scan --json --top 60 --min-calls 1`, connected as the read-only **`pglens_ro`**
  role — exactly how it runs against a real database.
- **Measurement:** for each planner-validated recommendation, `CREATE INDEX` on the throwaway copy,
  then `EXPLAIN (ANALYZE, BUFFERS)` each of that query's instances; **median of 3 warm runs**,
  parallelism + JIT off; drop the index; next. Measured drop = `1 − Σafter / Σbefore` over the
  query's instances.
- **Machine:** Apple M3 Pro, Docker Desktop 29.7 (11 CPUs, 7.75 GiB to the VM). The whole dataset fits
  in memory. Timings on another machine will differ; the before/after *ratio* is the result.

## Method note — why warm **time**, not buffers (a change made after the first run)
The first run used "buffers touched" (shared hit + read) as the win metric, copying the dogfood
benchmark. That turned out to be the wrong metric for this workload, and the change was made **after
seeing results**, so both are published. Buffers counts every *visit* to a page: an index probed
inside a nested loop re-visits the same cached pages many times while doing far less work than a
sequential scan. In this run `partsupp(ps_suppkey)` cut Q11's time by **69.9 %** while *doubling*
buffers (+102.9 %); `orders(o_custkey)` cut Q22 by 65.8 % with +33.9 % buffers. The dogfood benchmark
compared scan-vs-scan, where buffers is the right, cache-independent metric; here everything is
cached and the plans change shape, so warm execution time is the honest measure of work. By buffers
the precision below would be **2 / 14 (14 %)**; by time it is **11 / 14 (79 %)**.

## Results — every planner-validated recommendation, built and measured
| TPC-H Q | Index | Generic est. | Value-range floor | Measured time | Measured buffers | Win (time ≥ 15 %)? |
|---|---|---|---|---|---|---|
| 20 | `lineitem (l_shipdate)` | −73.8 % | −73.8 % | **−76.0 %** (103,915 → 24,949 ms) | −6.2 % | yes |
| 17 | `lineitem (l_partkey)` | −90.0 % | −90.0 % | **−99.5 %** (35,588 → 172 ms) | −99.5 % | yes |
| 9 | `lineitem (l_partkey)` | −85.3 % | — | −15.1 % (456 → 387 ms) | +54.5 % | yes (barely) |
| 21 | `lineitem (l_suppkey)` | −53.9 % | −53.9 % | −55.9 % (253 → 112 ms) | +16.7 % | yes |
| 19 | `lineitem (l_quantity)` | −47.1 % | −47.1 % | **−0.1 %** (218.7 → 218.5 ms) | −0.0 % | **no** |
| 14 | `lineitem (l_shipdate)` | −57.1 % | — | −78.3 % (141 → 31 ms) | −61.3 % | yes |
| 8 | `orders (o_orderdate)` | −34.4 % | −34.4 % | −47.4 % (172 → 90 ms) | +28.5 % | yes |
| 6 | `lineitem (l_shipdate)` | −66.1 % | — | −56.7 % (143 → 62 ms) | −6.4 % | yes |
| 6 | `lineitem (l_discount)` | −66.1 % | — | −26.5 % (143 → 105 ms) | +3.8 % | yes |
| 7 | `lineitem (l_suppkey)` | −48.7 % | −48.7 % | **−0.7 %** (109.1 → 108.3 ms) | −6.9 % | **no** |
| 22 | `orders (o_custkey)` | −76.8 % | — | −65.8 % (62.7 → 21.5 ms) | +33.9 % | yes |
| 2 | `part (p_size)` | −24.6 % | −23.8 % | −14.6 % (49.2 → 42.0 ms) | −2.7 % | **no** (just under) |
| 5 | `orders (o_orderdate)` | −39.7 % | −39.7 % | −17.1 % (68.6 → 56.9 ms) | −0.1 % | yes |
| 11 | `partsupp (ps_suppkey)` | −82.6 % | −82.6 % | −69.9 % (51.7 → 15.6 ms) | +102.9 % | yes |

(Estimates are HypoPG **planner estimates** of cost; the measured columns are real. A row is one
(query, index) pair — the same index appears for several queries, e.g. `lineitem(l_shipdate)`.)

## What it shows
- **Capture coverage was the first real finding.** Before the fix, **6 of 21 statements (29 %) could
  not be planned at all** from their normalized text, so PgLens never analyzed them — invisible on the
  self-written demo. Two normalization artifacts caused five of them and are now rewritten
  (ADR-0039): `extract(year FROM x)` normalizes to `extract($1 FROM x)` (a syntax error →
  `date_part($1, x)`), and literal arithmetic like `10 + 10` becomes untyped `$8 + $9` ("operator is
  not unique" → typed as numeric). **Coverage: 20 / 21.** The last one (Q12: an untyped
  `CASE … THEN $N` inside `sum()`) needs real type inference — backlog B9.
- **Precision: 11 of 14 validated (query, index) pairs cut warm time by ≥ 15 % (79 %).** PgLens's
  **#1** ranked recommendation, `lineitem(l_shipdate)` for Q20, was estimated at −73.8 % and measured
  at −76.0 %; **#2**, `lineitem(l_partkey)` for Q17, −90.0 % vs −99.5 % (35.6 s → 0.17 s).
- **The three misses share one cause:** the recommendation was validated on the *generic* plan, but
  with the real literal values the planner keeps the sequential scan. Q19's `l_quantity` is a **range**
  predicate inside an `OR` of three branches and Q7's is a join key under a date-range filter — neither
  is an equality parameter, so the value-range check (equality-only by design) can't catch it. `part
  (p_size)` is a near miss (−14.6 % vs the 15 % gate). This is the known limit of generic-plan
  validation, now measured: roughly 1 in 5 validated recommendations on this workload doesn't pay off.
- **Estimate error:** mean |estimated − measured time drop| is **22.7 points** for the generic
  estimate (14 recs) and **18.5 points** for the value-range floor (the 9 recs that have one — a
  different subset, so this is *not* a like-for-like improvement claim). TPC-H data is **uniformly
  distributed by design**, so the floor equals the generic estimate almost everywhere; the value-range
  feature earns its keep on **skewed** data (the demo: `orders(customer_id)` −98.7 % generic vs −56.7 %
  for the hot customer), which this benchmark doesn't exercise.
- **Not measured: recall.** There is no ground-truth list of "the indexes TPC-H needs", so this
  measures whether PgLens's recommendations are *right*, not whether it *missed* any. It also
  suppressed 36 candidates; whether any of those would have helped is not measured.
- **Noise:** two full runs differed by up to ~5 points on the same rec (Q20: −71.8 % vs −76.0 %;
  `p_size`: −11.8 % vs −14.6 %). Treat single-digit differences as noise.

## Reproduce
`make accuracy` (≈ 40 min on the machine above; most of it is the unindexed Q17/Q20 baselines).
`SF`, `SEEDS`, `REPEAT`, `RUNS` are overridable; `KEEP=1` leaves the container up. Output:
`build/accuracy/results.md` + `results.json` + PgLens's own `pglens.json`.

---

# PgLens dogfood benchmark — tuning our own metadata schema

> **PgLens finding a missing index on itself.** The metadata store (`query_stats`) is a real, growing
> time-series. Its trend / top-mover queries scan by `captured_at` **range across all queryids** — a
> path the V1 primary key `(db_id, queryid, captured_at)` does **not** serve (`captured_at` is the
> third key column). We **deliberately withheld** the time-series index from V1 (see the DOGFOOD NOTE
> in `server/.../V1__init.sql`) so this exercise could measure a **real** before/after and pick the
> index on evidence, not assertion. This is the Phase 2, Step 11 deliverable.

Everything below is real. **Planner cost is a labeled estimate**; **execution time and buffers are
measured** (`EXPLAIN (ANALYZE, BUFFERS)`, median of 5 warm runs, parallelism + JIT off so the number
reflects the *access path*, not worker scheduling or JIT compile jitter). Reproduce with
`scripts/dogfood_benchmark.sh` (or `make bench`).

---

## Setup

- **Image / schema:** `pgvector/pgvector:0.8.6-pg16` (the pinned metadata-db image), the **real**
  Flyway migrations `V1__init.sql`…`V3__index_hygiene.sql` applied verbatim.
- **Backfill:** one monitored db, **120** distinct queryids, **26 weeks** of snapshots at a **15-min**
  cadence → **2,096,640** `query_stats` rows, **363 MB**. Rows are inserted in `captured_at` order —
  exactly how the append-only ingest writes them — so the physical/time correlation BRIN relies on is
  **faithful, not staged**.
- **Honesty caveat:** the row *values* (calls, exec-time deltas) are synthetic random data. Only the
  table's **size** and **physical order** drive the result; no claim is made that these deltas model a
  real workload. What is measured — the cost, time, and buffers of the scan over 2.1 M rows — is real.
- **Queries under test:** the actual `TrendRepository` SQL, verbatim, with concrete window bounds —
  `windowTotals` (week-over-week top movers: a **14-day** cross-query scan) and `newQueries`
  (new-slow-query detection: a **7-day** scan + a first-seen `NOT EXISTS` anti-join).

## Method note — why buffers, not just wall-clock

The whole 363 MB table fits in this box's OS cache, so **warm wall-time understates the win**. The
cache-independent signal is **buffers touched** (shared hit + read): it is what determines latency
once the working set outgrows RAM — which an ever-growing time-series *will* do. Read the buffer
column as the real result; read wall-time as a warm-cache floor.

---

## Result 1 — `windowTotals` (week-over-week top movers)

| State | Scan node on `query_stats` | Planner cost *(est.)* | Exec median *(measured, warm)* | Buffers *(measured)* | Index size |
|---|---|---:|---:|---:|---:|
| **BEFORE** (no trend index) | `Nested Loop` → 120× PK `Index Scan` | 70,491 | 101 ms | **162,961** | — |
| **AFTER `BRIN(captured_at)`** | `Bitmap Heap Scan` (BRIN) | 37,909 | 85 ms | **2,238** | **24 kB** |
| **AFTER `btree(db_id, captured_at)`** | `Bitmap Heap Scan` (btree) | 40,174 | 77 ms | 2,309 | 15 MB |

**Buffer reduction: ~73× (BRIN), ~71× (btree).**

What the planner actually does (verbatim excerpts):

```text
BEFORE — the PK *can* answer this, but must touch every row in the window across all 120 queries:
  HashAggregate  (actual time=106.898..106.950 rows=120)
    Buffers: shared hit=162244 read=717
    ->  Nested Loop  (actual rows=161280)
          ->  Seq Scan on query_texts t  (rows=120)
          ->  Index Scan using query_stats_pkey on query_stats s  (rows=1344 loops=120)
                Index Cond: ((db_id=1) AND (queryid=t.queryid) AND (captured_at >= …14 days…) AND (captured_at < …))
                Buffers: shared hit=162242 read=717        <-- 120 per-query range scans, every row heap-fetched

AFTER BRIN — reads only the ~2,228 physically-contiguous heap blocks the 14-day window occupies:
  HashAggregate  (actual time=88.599..88.651 rows=120)
    Buffers: shared hit=2238
    ->  Hash Join  (actual rows=161280)
          ->  Bitmap Heap Scan on query_stats s  (actual rows=161280)
                Heap Blocks: lossy=2228
                ->  Bitmap Index Scan on query_stats_captured_brin
                      Buffers: shared hit=8       <-- the entire index probe is 8 buffers; the index is 24 kB
```

The PK is *not* naive here — it drives 120 tight per-query range scans — but it still heap-fetches
every row in the window (~163k buffer accesses). BRIN prunes to the block ranges overlapping the time
window; because the data is appended in time order, that window is **contiguous**, so a single
`Bitmap Heap Scan` reads ~2,228 blocks in physical order. (BRIN is block-range-lossy — note the 5,760
rows removed by recheck — which is cheap and expected.)

## Result 2 — `newQueries` (new-slow-query detection)

| State | Planner cost *(est.)* | Exec median *(measured, warm)* | Buffers *(measured)* |
|---|---:|---:|---:|
| **BEFORE** (no trend index) | 98,766 | 185 ms | **404,174** |
| **AFTER `BRIN(captured_at)`** | 69,992 | 176 ms | 323,659 |
| **AFTER `btree(db_id, captured_at)`** | 71,337 | 171 ms | 323,729 |

**Buffer reduction: only ~1.25×.** This query is **bound by the first-seen `NOT EXISTS` anti-join**
(`p.db_id=… AND p.queryid=… AND p.captured_at < recentFrom`), which the existing PK already serves
**correctly and well** — that is what the ~324k residual buffers are. The trend index only accelerates
the 7-day *outer* scan. Honest conclusion: **the trend index primarily benefits the cross-query
aggregation (top-movers / leaderboard), not the anti-join-bound new-query detector** — and that's
fine. We do not over-index a path that is already optimal.

---

## Decision — add `BRIN(captured_at)` (migration V4, ADR-0033)

**BRIN wins the trade-off decisively:**

- It delivers essentially the **same read reduction as the 15 MB btree (~73×)** at **24 kB** —
  **~625× smaller**.
- `captured_at` is **append-only and physically time-ordered**, the textbook fit for BRIN: block-range
  summaries stay tight, and maintenance on insert is near-free (no per-row btree page splits) — which
  matters for an insert-heavy time-series ingested every cycle.
- The btree's only edge is a **marginal warm wall-time lead** (77 vs 85 ms) that (a) is within the
  noise the buffer metric sees through, and (b) does not justify 625× the storage and real per-ingest
  write amplification.

**Trade honestly recorded:** BRIN's win depends on the physical/time correlation of `captured_at`.
Ingest appends in time order, so this holds; heavy out-of-order backfill or clock-skewed multi-agent
interleaving would widen block-range min/max and blunt BRIN — autovacuum/`brin_summarize` keeps it
summarized. If PgLens ever hosts **many** monitored DBs in one metadata store and per-db isolation
(not just time-window pruning) becomes the bottleneck, `btree(db_id, captured_at)` is the measured
alternative — recorded as [backlog B11](backlog.md), not built now (YAGNI: Phase 2 is single-tenant).

The index ships in `server/.../V4__query_stats_trend_brin.sql`. The dogfood note in `V1__init.sql`
(and the "leave it unindexed" language in `TrendRepository`) is now fulfilled: this benchmark is the
measurement it pointed to, and V4 is the index it promised.

## Reproduce

```bash
make bench            # or: bash scripts/dogfood_benchmark.sh
KEEP=1 make bench     # leave the throwaway container up to poke at
```

The script spins a throwaway `pgvector` container (never the live stack, never a monitored DB),
applies V1–V3, backfills, and prints the table above. Numbers are stable run-to-run within ~2 %
(the buffer counts are identical); absolute wall-times depend on the host.
