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
