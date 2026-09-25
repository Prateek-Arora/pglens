# PgLens benchmarks

Three benchmarks, all reproducible, all **measured** (no fabricated numbers — charter #1):

1. [**Recommendation accuracy on an external workload**](#accuracy-benchmark--tpc-h-derived-workload-phase-25) — `make accuracy` (Phase 2.5, ADR-0038/0039; re-measured under server settings, ADR-0041).
2. [**Recommendation accuracy on real skewed data — JOB/IMDB**](#accuracy-benchmark-2--join-order-benchmark-on-real-imdb-data-pre-registered) — `make accuracy-job` (pre-registered).
3. [**Dogfood: tuning PgLens's own metadata schema**](#pglens-dogfood-benchmark--tuning-our-own-metadata-schema) — `make bench` (Phase 2, ADR-0033).

---

# Accuracy benchmark — TPC-H-derived workload (Phase 2.5)

> **The question:** when PgLens says "create this index", is it right — on a workload **we did not
> write**? Every earlier accuracy test used `demo/slow_queries.sql`, which was authored to be caught.
> Here PgLens runs against a public benchmark schema, then **every index it validates is built for
> real** on a throwaway copy and measured. First run 2026-09-24; **re-measured 2026-09-25 under the
> server's own settings, which changed the result** (next section).

## Current result (2026-09-25) — measured under the server's own settings
The 2026-09-24 table below was measured with parallelism and JIT turned off and `work_mem` raised.
The JOB run (ADR-0040) showed that those "pinned" settings change the planner's choices, so the
measured plans weren't the ones the workload runs. Re-measured with the server's own settings (the
ones the workload ran with and PgLens validated under) and `EXPLAIN (ANALYZE, TIMING OFF)`, twice:

| TPC-H Q | Index | Generic est. | Pinned (09-24, superseded) | Server settings, run 1 | Run 2 | Win? |
|---|---|---|---|---|---|---|
| 20 | `lineitem (l_shipdate)` | −73.8 % | −76.0 % | −61.7 % (59.5 s → 22.8 s) | −62.4 % | yes |
| 17 | `lineitem (l_partkey)` | −90.0 % | −99.5 % | −99.7 % (34.3 s → 0.12 s) | −99.7 % | yes |
| 9 | `lineitem (l_partkey)` | −85.3 % | −15.1 % | **+28.6 % slower** | +28.9 % | **no** |
| 21 | `lineitem (l_suppkey)` | −53.9 % | −55.9 % | **+18.5 % slower** | +15.8 % | **no** |
| 19 | `lineitem (l_quantity)` | −47.1 % | −0.1 % | +1.5 % | +0.9 % | **no** |
| 6 | `lineitem (l_discount)` | −66.1 % | −26.5 % | **+14.7 % slower** | +15.4 % | **no** |
| 6 | `lineitem (l_shipdate)` | −66.1 % | −56.7 % | −33.9 % | −33.0 % | yes |
| 22 | `orders (o_custkey)` | −76.8 % | −65.8 % | −56.2 % | −57.9 % | yes |
| 14 | `lineitem (l_shipdate)` | −57.1 % | −78.3 % | −56.7 % | −56.9 % | yes |
| 7 | `lineitem (l_suppkey)` | −48.7 % | −0.7 % | **+63.5 % slower** | +62.7 % | **no** |
| 11 | `partsupp (ps_suppkey)` | −82.6 % | −69.9 % | −55.8 % | −57.6 % | yes |
| 8 | `orders (o_orderdate)` | −34.4 % | −47.4 % | +1.8 % | +0.6 % | **no** |
| 5 | `orders (o_orderdate)` | −39.7 % | −17.1 % | −10.7 % | −9.6 % | **no** |
| 2 | `part (p_size)` | −24.6 % | −14.6 % | −12.1 % | −13.2 % | **no** |

- **Precision: 6 of 14 (43 %)** in both runs — down from the 11 / 14 (79 %) published on 09-24.
  **4 of 14 made their query slower** (by 15–64 %). With parallel sequential scans (the default), the
  unindexed baseline is much faster, and several index plans the planner prefers lose to it.
- **Noise is small under these settings:** the two runs differ by a median of 0.8 points (max 2.8).
- PgLens's #1 and #2 (`lineitem (l_shipdate)` for Q20, `lineitem (l_partkey)` for Q17) are still
  large real wins — the two biggest queries, 59.5 s → 22.8 s and 34.3 s → 0.12 s.
- Mean |estimate − measured| is 43 points; Spearman between estimated and measured drop 0.38–0.41.
- **What this means:** on this workload the planner's "this index helps" is right less than half
  the time once the database runs normally. It's the strongest evidence yet that PgLens must not
  present a planner-validated index as safe to apply (ADR-0041) — and the case for Phase 2.6.

The rest of this section is the original 2026-09-24 write-up, kept for the record. Its precision and
misses are **superseded** by the table above.

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
`make accuracy` (≈ 12 min with the default server settings; `MEASURE_SETTINGS=pinned` reproduces the
09-24 table in ≈ 40 min).
`SF`, `SEEDS`, `REPEAT`, `RUNS` are overridable; `KEEP=1` leaves the container up. Output:
`build/accuracy/results.md` + `results.json` + PgLens's own `pglens.json`.

---

# Accuracy benchmark 2 — Join Order Benchmark on real IMDB data (pre-registered)

> **The question:** TPC-H's data is uniform, so it couldn't test the Phase 2.5 value-range floor
> (ADR-0038). Does ranking by the worst-case floor beat the generic-plan estimate on **real, skewed
> data with queries we did not write**? And does the 79 % precision from TPC-H hold?
>
> **Pre-registered 2026-09-25, before any JOB run.** The setup, metrics and decision rule below were
> written down first and are not changed after seeing results. If the run exposes an engine bug, run 1
> stays the reported result; a fix is re-run and reported separately as run 2.

## Setup (fixed in advance)
- **Data:** the IMDB snapshot (May 2013) used in the JOB paper — Leis et al., *How Good Are Query
  Optimizers, Really?*, PVLDB 9(3), 2015 — downloaded at bench time from CWI
  (`event.cwi.nl/da/job/imdb.tgz`, not vendored; IMDB's non-commercial terms apply). Real data,
  heavily skewed (country codes, info types, keywords, cast roles).
- **Queries:** all **113 JOB queries** from `gregrahn/join-order-benchmark` @ `a396036`, unmodified.
  Written by the paper's authors to stress join ordering, so they are analytic multi-way joins, **not
  ORM-style OLTP** — this run doesn't cover hot-key point lookups.
- **Schema:** the repo's `schema.sql` (**primary keys only**; `fkindexes.sql` is **not** applied), so
  foreign-key and filter indexes are for PgLens to find. Same PG16 monitored image as run 1.
- **Workload:** each query replayed **once** into `pg_stat_statements` (`statement_timeout` 600 s; a
  query that times out or fails is listed and left out).
- **PgLens:** `pglens scan --json --top 150 --min-calls 1` as the read-only `pglens_ro` role.
- **Measurement:** unchanged from TPC-H run 2 — build each planner-validated index for real, then
  `EXPLAIN (ANALYZE, BUFFERS)` the query's own instances: 1 warm-up + **median of 3 warm runs**,
  parallelism + JIT off, `work_mem` 64 MB, 900 s timeout. A measurement that times out is reported as
  *not measured* (neither win nor miss).

## Metrics and decision rule (fixed in advance)
Definitions: *measured drop* = `1 − Σafter / Σbefore` warm execution time over the query's instances;
*win* = measured drop ≥ 15 %; *floor* = what PgLens ranks by when a value range exists,
`min(generic, worst)` clamped at 0 (`RankingScore`); a rec is *cautioned* when its worst case is below
the 15 % gate.

- **M1 (primary) — is the floor closer to reality than the generic estimate?** On the measured recs
  whose floor differs from the generic estimate by **≥ 5 points**, mean |estimate − measured drop| for
  the floor vs for the generic estimate, **on the same recs**, plus how many recs each is closer on.
  (Also reported over every rec with a range.)
- **M2 — does the caution predict misses?** Miss rate of cautioned recs vs uncautioned recs.
- **M3 — precision:** wins / measured recs.
- **M4 — coverage:** plans captured / statements analyzed.
- **M5 — ranking quality:** Spearman correlation between measured drop and (a) the drop PgLens ranks by
  vs (b) the generic drop alone, over all measured recs.
- **Decision rule:** the floor *helps on this workload* only if M1's floor error is lower **and**
  cautioned recs miss more often than uncautioned ones. Fewer than 5 recs in M1's subset →
  *inconclusive*, not a win. Differences under ~5 points are noise (TPC-H run-to-run).
- **Not measured:** recall (no ground truth), write overhead, ORM-style OLTP.

## Deviations from the pre-registration (recorded before any before/after result was seen)
1. **Measurement session settings → the server's own.** The pre-registered settings (parallelism +
   JIT off, `work_mem` 64 MB) made the planner choose *different plans* from the ones the workload ran
   and PgLens validated under: JOB 31a hit the 600 s timeout with them (plain execution, not just
   under EXPLAIN) but runs in ~2 s with the server's settings. Measuring with them would time plans
   the workload never runs, so the JOB run measures with the server's settings (only a 900 s
   timeout; `MEASURE_SETTINGS=server`). Found during the baseline phase, before any index was built.
   *Consequence for TPC-H:* the published TPC-H numbers were measured with the pinned settings while
   PgLens validated under server settings — the same mismatch. *Re-run 2026-09-25 under server
   settings: precision fell from 11/14 to 6/14 (see the TPC-H section).*
   Same profile: **per-node timing off** (`EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)`). EXPLAIN
   ANALYZE's clock reads inflate nested-loop-heavy plans far more than others — JOB 28a took 197 s
   with timing off and was still running after 360 s with it on — so they'd bias the before/after
   ratio. Execution time and buffers are unaffected by the option.
2. **The harness now records errors it hadn't anticipated.** The first recommendation measured,
   `movie_info (info)`, **could not be built at all** (a B-tree entry was too wide). The harness
   crashed; it now records such recs as *could not be built* and reports them separately, and as
   misses in a second precision figure. It also builds each distinct index once for all its queries
   (same numbers, far fewer builds). The load and PgLens's scan were not re-run.
3. **Found after the run, fixed for next time:** one measurement (19d with `cast_info (note)`)
   failed because Docker's default 64 MB `/dev/shm` is too small for a parallel hash join. It is
   reported as *not measured*; the harness now starts the container with `--shm-size=1g`.

## Results (run 1, 2026-09-25)
Machine as for TPC-H (Apple M3 Pro, Docker VM 7.75 GiB, 11 CPUs); PostgreSQL 16, hypopg 1.4.x. IMDB
loaded at **7.0 GB** (`cast_info` 36.2 M rows, `movie_info` 14.8 M), so it roughly fills the VM's
memory: "warm" here means OS-cache-warm, not all-in-memory. Replay: 113 queries, **0 failures**.
PgLens analyzed 99 statements and proposed **214 planner-validated recommendations**, which name only
**10 distinct indexes** (the same foreign-key index helps many queries).

| Pre-registered metric | Result |
|---|---|
| **M4** coverage | **99 / 99** statements planned — no capture gaps on JOB |
| **M3** precision (warm time ↓ ≥ 15 %) | **128 / 179 measured = 72 %** (by buffers: 99 / 179 = 55 %). Counting the 34 recs whose index **could not be built** as misses: **128 / 213 = 60 %** |
| **M1** primary — floor vs generic, same recs, floor ≥ 5 pts below generic | **8 recs:** mean error generic **30.2** pts vs floor **46.0** pts; generic closer on **6 of 8** |
| M1 — every rec with a range | 66 recs: generic 43.3 vs floor 45.1 pts; floor closer on 21, generic on 15 (the rest tie) |
| **M2** does the caution predict misses? | Cautioned recs missed **1 / 5 (20 %)**; uncautioned **50 / 174 (29 %)** — **no** |
| **M5** Spearman with measured drop (179 recs) | ranked-by estimate **0.04**, generic alone **0.08** — essentially no correlation |

**Decision rule → the value-range floor does *not* help on this workload.** Its error was higher on
the recs where it disagreed with the generic estimate, and the caution didn't predict misses. The
subset is small (8), but it clears the pre-registered minimum of 5, so the verdict stands as written.

Per index (a "rec" is one (query, index) pair; measured drop = warm time, server settings):

| Index | Recs measured | Wins | Median measured drop | Median generic est. | ≥ 5 % slower |
|---|---|---|---|---|---|
| `movie_info (info)` | 0 of 34 | — | **could not be built** | −68 % (rank #2) | — |
| `cast_info (movie_id)` | 47 | 27 | −31 % | −82 % | **15** |
| `movie_info (movie_id)` | 45 | 35 | −38 % | −71 % | 4 |
| `cast_info (note)` | 26 (+1 not measured) | 23 | −42 % | −55 % | 0 |
| `cast_info (person_id)` | 15 | 15 | −65 % | −50 % | 0 |
| `cast_info (role_id)` | 14 | **4** | **+8 %** (slower) | −63 % | **8** |
| `movie_companies (movie_id)` | 12 | 10 | −68 % | −65 % | 0 |
| `movie_keyword (movie_id)` | 8 | 5 | −24 % | −49 % | 3 |
| `movie_keyword (keyword_id)` | 6 | 4 | −58 % | −55 % | 2 |
| `movie_info_idx (movie_id)` | 6 | 5 | −43 % | −25 % | 0 |

What a user sees — PgLens's list has **one line per index** (the same index recommended for several
queries is merged). Estimated = the sum of PgLens's per-query estimates for that index (generic plan ×
the query's real total time); measured = the sum over those queries of warm time before − after.
*This per-index view is an analysis added after the run, not a pre-registered metric.*

| List # | Index | Queries | Estimated saving | Measured saving | Faster / slower (≥ 15 % / ≥ 5 %) |
|---|---|---|---|---|---|
| 1 | `movie_info (movie_id)` | 45 | 175.0 s | **363.8 s** (423.9 → 60.1 s) | 35 / 4 |
| 2 | `movie_info (info)` | 34 | 130.5 s | **could not be built** | — |
| 3 | `cast_info (movie_id)` | 47 | 134.5 s | 26.6 s (253.4 → 226.9 s) | 27 / **15** |
| 4 | `cast_info (person_id)` | 15 | 63.6 s | **145.4 s** (170.0 → 24.5 s) | 15 / 0 |
| 5 | `cast_info (note)` | 26 | 84.2 s | 65.6 s (208.0 → 142.4 s) | 23 / 0 |
| 6 | `cast_info (role_id)` | 14 | 10.0 s | 0.8 s | 4 / **8** |
| 7 | `movie_keyword (keyword_id)` | 6 | 3.9 s | 6.2 s | 4 / 2 |
| 8 | `movie_keyword (movie_id)` | 8 | 2.4 s | 2.2 s | 5 / 3 |
| 9 | `movie_companies (movie_id)` | 12 | 3.3 s | 5.0 s | 10 / 0 |
| 10 | `movie_info_idx (movie_id)` | 6 | 0.3 s | 0.7 s | 5 / 0 |

(The list order follows PgLens's rule: each index sits at its best single query's score. The run
used v0.0.4, which ranked by the value-range floor; re-ranked offline by the generic estimate
(ADR-0041) the list is identical except #8 and #9 swap.)

At the level a user acts on, the ranking holds up: the index-level rank correlation between
estimated and measured saving is **0.83** (9 buildable indexes; 0.83 for the floor-based sums too).
The #1 index saved 364 s of the workload's 424 s on its queries. The per-query percentages are
what's unreliable — and `cast_info (movie_id)`, #3, saved little overall because it made 15 of its 47
queries slower.

Full per-rec table: `build/accuracy-job/results.md` after `make accuracy-job`.

## What it shows
- **The ranking still finds the big wins.** PgLens's #1 index saved 364 of 424 s on its queries,
  and the per-index ranking correlates well with the measured saving (0.83). For **79 of the 90**
  statements with a measured rec, at least one recommended index cut warm time by ≥ 15 %. The score
  is dominated by the query's real total time, and that part works.
- **The *size* of the estimate means almost nothing on JOB.** Spearman ≈ 0; mean error 47 points;
  the generic estimate overstated the win by more than 10 points on 101 of 179 recs. JOB was built to
  expose the planner's cardinality misestimates, and HypoPG's numbers are the planner's numbers.
- **"Planner-validated" is not "safe".** **32 of 179 recs (18 %) made their query ≥ 5 % slower; 7
  made it more than 2× slower.** Worst: 10c with `cast_info (movie_id)`, estimated −91 %, measured
  0.93 s → 7.3 s (re-verified by hand: ~1 s → ~9 s). The new index lets the planner choose a nested
  loop that probes `cast_info` once per row of a join whose size it under-estimates. Any HypoPG-based
  advisor inherits this; the only real check is running the query with the index (backlog B6).
  `cast_info (role_id)` — a 12-value column — won on only 4 of 14 queries.
- **One recommendation can't be applied at all.** `movie_info (info)`, PgLens's #2, fails with
  "index row requires 9392 bytes, maximum size is 8191": 1,182 of 14.8 M values are longer than a
  B-tree entry allows (~2.7 kB; the longest is 19.8 kB). HypoPG never writes an entry, so it can't
  know. That's 34 of 214 recs (16 %) → backlog B17. *Since ADR-0041 PgLens flags it:* on a re-scan
  of the same data, the build caution fired on `movie_info (info)` (its table stores 3.3 MB out of
  line) and on none of the other 10 indexes — including the other `text` one, `cast_info (note)`,
  whose table stores nothing out of line.
- **Why the floor lost.** For `cast_info (note)` the worst case came from the column's most common
  value (3.8 % of rows), where the planner sees no gain — so the floor said 0 %. But JOB's queries
  filter on *other* notes, and the measured wins were 7–79 %. The floor is right when the workload
  queries its hot values (the demo) and wrong when it doesn't; `pg_stat_statements` doesn't keep the
  values, so PgLens can't tell which. Evidence so far: the demo (1 case for), JOB (6 of 8 against).
- **Not measured:** recall; write overhead; ORM-style OLTP (JOB is analytic joins); 1 rec (shm).

## The same check as a product command — `pglens confirm` (Phase 2.6, 2026-09-25)

*Added after the results above; not part of the pre-registration.* The Phase 2.6 DoD asks whether the
user-facing `pglens confirm` reproduces this harness's per-index verdicts. Setup: the same IMDB load
and image (server settings); JOB replayed once; `pglens scan --json --top 150` as `pglens_ro`; the
database cloned with `CREATE DATABASE imdb_copy TEMPLATE imdb` (a physical copy, like a Neon branch)
and marked `pglens.scratch = 'on'`; then `pglens confirm --top 10 --statement-timeout 600` with the 113
JOB `.sql` files as the statements. It matched **95 of 98 query shapes** to report queries (every
query behind the top 10 indexes had its statement) and ran 79 min.

| # | Index | This harness (ADR-0040), per index | `pglens confirm` | Same verdict? |
|---|---|---|---|---|
| 1 | `movie_info (movie_id)` | −85.8 % (423.9 → 60.1 s), faster | −73.0 % (454.5 → 122.5 s), faster | ✅ |
| 2 | `movie_info (info)` | could not be built | couldn't be built: "index row requires 9392 bytes, maximum size is 8191" | ✅ |
| 3 | `cast_info (movie_id)` | −10.5 % (253.4 → 226.9 s), no real effect; 15 queries slower | −18.0 % (166.4 → 136.5 s), faster; **19 of 47 queries slower** | ❌ |
| 4 | `cast_info (person_id)` | −85.6 %, faster | −69.8 %, faster | ✅ |
| 5 | `cast_info (note)` | −31.5 %, faster | −23.6 %, faster | ✅ |
| 6 | `movie_keyword (keyword_id)` | −81.5 %, faster | −86.1 %, faster | ✅ |
| 7 | `cast_info (role_id)` | −4.4 % (18.6 → 17.8 s), no real effect; 8 queries slower | −17.7 % (28.5 → 23.4 s), faster; 6 of 15 slower | ❌ |
| 8 | `movie_companies (movie_id)` | −57.6 %, faster | −56.5 %, faster | ✅ |
| 9 | `movie_keyword (movie_id)` | −42.3 %, faster | −60.5 %, faster | ✅ |
| 10 | `movie_info_idx (movie_id)` | −47.0 %, faster | −35.1 %, faster | ✅ |

- **The self-check passes:** 10c under `cast_info (movie_id)` — estimated −90.7 % — is reported
  **slower, 928.6 ms → 9,209.6 ms** (the harness: 931 → 7,277 ms).
- **8 of 10 verdicts agree.** The two that differ are the two `cast_info` indexes that slow many of
  their queries, and both land just past the 15 % line. The per-query picture is the same in both
  runs; the totals move because a few of JOB's heaviest plans vary between runs *without* any index:
  29c's baseline was 125.9 s in the harness run and 39.2 s here, 30a's 23.4 s vs 1.3 s.
- **Run-to-run variation, measured:** a second `confirm` run of just those two indexes gave
  *faster* again for both, but at −37.8 % and −37.6 % (vs −18.0 % / −17.7 %). The time *with* the
  index hardly moved (29b 17.8 → 18.0 s, 26c 11.7 → 11.9 s; 10c again slower, 964 ms → 9,418 ms);
  the unindexed baselines of a few heavy queries did (30a 1.3 → 34.1 s, 29b 1.5 → 17.9 s, 26c 1.8 →
  12.9 s). So for these two indexes the verdict *near the 15 % line* is not stable across runs,
  while a large effect (10c's 10× slowdown, the other 8 indexes) is. Likely cause `[ASSUMPTION — not
  isolated]`: on this machine the original and the copy (7 GB each) share a 7.7 GiB Docker VM with
  128 MB `shared_buffers`, so "warm" isn't fully warm for the heaviest JOB queries.
- **What a user should take from it:** an index-level "faster" can hide slowed queries — which is
  why `confirm` prints a verdict per query and warns "faster overall, but slower for N of its
  queries". A total that sits near a threshold is worth a second run.

- **Reproduce:** load and replay as `make accuracy-job` does (`KEEP=1` keeps the container), then
  `pglens scan … --json --top 150 > scan.json`, `CREATE DATABASE imdb_copy TEMPLATE imdb`,
  `ALTER DATABASE imdb_copy SET pglens.scratch = 'on'`, and `pglens confirm --report scan.json --copy
  postgresql://pglens:pglens@localhost:<port>/imdb_copy --statements <each JOB .sql> --top 10
  --statement-timeout 600`.

## Reproduce
`make accuracy-job` — downloads the 1.26 GB IMDB snapshot once into `build/accuracy-cache/`
(sha256 `25f9d893…a7e988`), loads it (~10 min), replays, scans, then builds and times each distinct
index (≈ 1.5–2 h on the machine above; the unindexed 28a/29c baselines take minutes each). Output:
`build/accuracy-job/results.md` + `results.json` + `pglens.json`.

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
