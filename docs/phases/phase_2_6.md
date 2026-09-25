# PgLens · Phase 2.6 — Confirm on a Copy (measured index checks)

> **Status: APPROVED 2026-09-25 (user) — BUILT + VERIFIED 2026-09-26 (ADR-0042), not yet committed.**
> Step 0 ran first; its results changed Steps 1–3 (see §3a). Written after the JOB/IMDB benchmark (ADR-0040) showed that 18 % of
> planner-validated recommendations made their query slower; it turns backlog **B6** into a phase.
>
> **Self-contained.** Prereqs: Phases 0–2.5 (`v0.0.4`) + the ADR-0041 follow-ups (generic ranking,
> B17 build caution, server-settings benchmark method). **Tag:** `v0.0.5` (Phase 3 moves to `v0.0.6`).

## 1. Context — why this phase, and why before Phase 3

PgLens's HypoPG gate answers "does the planner *think* this index helps?". On JOB/IMDB the planner's
row estimates were wrong often enough that **32 of 179 validated recommendations made their query
≥ 5 % slower (7 of them more than 2×)** — HypoPG repeats the planner's estimates, so no amount of
estimate-tuning fixes that. The only real check is to **build the index and run the query**. That
must never happen on the monitored database (charter #2), so it happens on a **copy** the user
provides. `make accuracy` / `make accuracy-job` already do exactly this for benchmarks; this phase
turns that harness into a safe, user-facing command.

Phase 3's LLM only phrases facts the engine owns (charter #3). "Measured on your copy: 3.1 s → 0.2 s"
is a far better fact to phrase than "the planner estimates −74 %".

## 2. Goal & non-goals

**Goal:** `pglens confirm` takes a scan report and a user-declared scratch copy, builds each
recommended index for real on the copy, times the workload's own statements before and after, and
reports a **measured** verdict per index — *faster*, *no real effect*, *slower*, *couldn't be built*,
or *not measured* — labeled as a copy measurement.

**Non-goals (YAGNI for this phase):** running anything against the monitored database; the agent or
server doing confirmations (the agent stays read-only — a server-side "confirm worker" with its own
credentials is a later item); creating the copy for the user (cloud APIs); `auto_explain` ingestion
(B6a); dashboard views (Phase 4 can read the JSON); `CREATE INDEX CONCURRENTLY` (a copy has no
live traffic).

## 3. Verification of the plan (what was checked, and what it changed)

| Claim / assumption | Checked how | Result |
|---|---|---|
| Measuring on a copy catches what HypoPG can't | ADR-0040: the JOB harness did exactly this | ✅ It found 32 slower recs and 34 unbuildable ones that the estimates hid. |
| Users can get a copy cheaply | Web, 2026-09-25 | ✅ Neon branches (copy-on-write, ~seconds); Aurora fast cloning (copy-on-write, minutes, 15 per source); RDS = snapshot restore (full copy); Supabase branches copy **no data** (not usable here); `pg_dump`/`pg_restore` anywhere. |
| Real statement values can be obtained | Local PG16 test, 2026-09-25 | ✅ With `log_min_duration_statement`, extended-protocol statements are logged as `execute <unnamed>: … $1 …` plus `DETAIL:  parameters: $1 = '2', $2 = 'it''s'` — values already quoted as SQL literals. Simple-protocol statements are logged whole (`statement: …`). |
| **pg_stat_statements has no values to run** (B6 blocker 1) | ADR-0013, B6 | ⚠ Still true, so **the user supplies real statements**: a `.sql` file or a PostgreSQL log. PgLens never invents values for a real execution. |
| **Statements can be matched to report queries by `queryid`** | Reasoned from how `queryid` is computed | ❌ **Not safely.** `queryid` hashes relation **OIDs** (different on a `pg_dump` restore), and a literal (`Const`) and a bound parameter (`Param`) jumble differently. **Changed the design:** match on the **normalized text**, produced by the copy's own `pg_stat_statements` — run each statement once, read back its normalized text, compare with the report's. Same engine, same normalization. `[ASSUMPTION — verify in Step 0]` |
| The copy won't be mistaken for production | Local PG16 test | ✅ A custom setting works as an opt-in marker: `ALTER DATABASE copy SET pglens.scratch = 'on'`; `current_setting('pglens.scratch', true)` returns `on` there and NULL anywhere it isn't set. A physical clone keeps the same `system_identifier`, so that can't tell prod from a clone — the marker can. |
| A measured statement can't write | PostgreSQL semantics | ✅ Run each measured statement in `BEGIN READ ONLY … ROLLBACK`: even a `SELECT` that calls a writing function fails. Only `CREATE INDEX` / `DROP INDEX` of PgLens-named indexes run outside that. |
| The copy predicts production | — | ⚠ Only approximately: hardware, cache and concurrent load differ. Every number is labeled "measured on the copy". |
| `log_min_duration_statement` samples fairly | PostgreSQL docs | ⚠ It logs only statements over the threshold, so the sample leans towards slow values — arguably the ones that matter; stated in the report. |

**Conflicts with past decisions:** ADR-0013 deferred `--explain-analyze`; ADR-0020 made scans
read-only. This phase keeps both for the **monitored** database and adds a separate, opt-in path that
writes only to a copy the user has marked. It needs its own ADR (proposed: ADR-0042).

## 3a. Step 0 results (spike, 2026-09-25, PG16 + PG17 + PG18)

Two databases on one server; the "copy" had an extra table first, so every relation OID differs.
Statements were sent by psql (simple protocol) and pgjdbc 42.7 (extended protocol, typed and `NULL`
parameters, an unnamed and a named `S_1` statement); then replayed on the copy.

| Replay of … | How | Copy's pg_stat_statements text vs the monitored DB's |
|---|---|---|
| a literal statement (simple or extended protocol, multi-line, comment, `''`) | run as logged | ✅ identical text (queryid differs — OIDs) |
| a `$N` statement + its logged parameters | `PREPARE pglens_sN AS <text>; EXECUTE pglens_sN(<logged literals>)` | ✅ identical **after removing the `PREPARE pglens_sN AS ` prefix** pg_stat_statements keeps — including `LIMIT $2` with a constant numbered `$3` (same on PG17 and PG18) |
| the same, values substituted into the text | textual `$N` → literal | ❌ `… id > $2 LIMIT $3` — constants renumber; **rejected** |
| `EXPLAIN ANALYZE …` of either | — | ❌ pg_stat_statements (`track = top`) records only the `EXPLAIN` utility statement, never the inner query — so matching needs **one plain execution** per query shape |

What that changed:
- **Replay:** literal statements run as logged; `$N` statements run through `PREPARE`/`EXECUTE`
  with `plan_cache_mode = force_custom_plan` (planned with the real values, as an unnamed extended-
  protocol statement is).
- **Matching:** group statements by the copy's own queryid (`EXPLAIN (VERBOSE)`, no execution); run
  **one** statement per group plainly (read-only transaction, rows streamed and discarded) so the
  copy's pg_stat_statements records its text; compare that text (prefix removed, whitespace
  collapsed) with the report's `normalizedText`.
- **Marker:** custom `pglens.*` settings are placeholders and don't appear in `pg_settings`, so their
  `source` can't be read there. The guard reads **`pg_db_role_setting`** instead — the marker must be
  set on the database (`ALTER DATABASE … SET pglens.scratch = 'on'`), not by a `SET` or a connection
  option.
- **Logs:** real PG16 output for both `log_min_duration_statement = 0` and `log_statement = 'all'`
  is the test fixture; `parse`/`bind` lines and `execute fetch from` continuations are skipped.
- **Report identity:** the scan report gains `target.port` (JSON 1.2 isn't released yet, so it's
  added there) so the guard can compare host + port + database with the copy.

## 4. Plan

**Step 0 — spike the matching (half a day, decides the design).** On PG16: [DONE — §3a] prove the copy's
`pg_stat_statements` produces the same normalized text as the monitored DB for (a) a simple-protocol
literal statement and (b) an extended-protocol `$N` statement run with its logged parameters (pgjdbc,
parameters sent untyped). Record the result in ADR-0042. If (b) fails, fall back to exact `$N`-text
equality for log entries and document the gap.

**Step 1 — statement sources (pure, `engine/confirm/StatementSource`).** Parse a `.sql` file
(statements separated by `;`, quotes and dollar-quotes respected) and a PostgreSQL stderr log
(`statement:` lines; `execute …:` lines with their `DETAIL:  parameters:` line; multi-line
statements). Keep `SELECT` statements only. Values stay in memory, on the user's machine — never in a
report or on the wire.

**Step 2 — the copy guard (`engine/db/CopyTarget`).** Refuse unless `pglens.scratch = 'on'` on the
copy database; refuse if host/port/db equal the report's monitored target; require
`pg_stat_statements`; check `CREATE` privilege up front with a clear message. On start, drop any
leftover `pglens_confirm_*` index from an interrupted run.

**Step 3 — measurement (`engine/db/CopyMeasurer`).** For each recommended index (top N from the
report, default 10), in PgLens rank order: build it as `pglens_confirm_<n>`; for each matched
statement: `EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)` inside `BEGIN READ ONLY … ROLLBACK`, 1 warm-up +
median of 3, server settings, statement timeout; drop the index in `finally`. Same method as
`scripts/accuracy/measure.py` (ADR-0040), so the benchmark and the product agree.

**Step 4 — verdicts (pure, `engine/confirm/Verdict`).** Per index, over all its matched statements:
measured drop = `1 − Σafter / Σbefore`. **Faster** ≥ 15 %; **slower** ≤ −5 %; **no real effect**
between; **couldn't be built** (the `CREATE INDEX` error, verbatim); **not measured** (no matched
statement, timeout, or rejected write). Show the HypoPG estimate beside the measurement.

**Step 5 — CLI (`cli/ConfirmCommand`).** `pglens confirm --report scan.json --copy <url>
--statements <file|log> [--top N] [--json]`. Human output plus a versioned JSON contract
(`ConfirmReport` 1.0). A dry-run mode lists what would be built and matched.

**Step 6 — docs.** README "Check before you apply" section (how to make a copy on Neon / Aurora /
RDS / `pg_dump`, how to set the marker, how to log statements); ADR-0042; architecture + project.

## 5. Definition of Done
- [x] Step 0 result recorded (§3a, ADR-0042); matching works for both protocols (literal: as logged; `$N`: PREPARE/EXECUTE).
- [x] `pglens confirm` refuses: no marker (and a marker set only by a connection option); the
      scanned target; no `pg_stat_statements`; a missing table; a non-owner — each with an
      actionable message (`CopyConfirmerIntegrationTest`). Also refuses a standby and pre-PG16.
- [x] A statement that tries to write (`SELECT bump(…)`, a writing SQL function) is rejected by the
      read-only transaction; its index is *not measured*; the table is unchanged and no
      `pglens_confirm_*` index remains; a leftover from an "interrupted" run is dropped (IT).
- [x] Verdicts reproduce on real data: *faster* (`orders(customer_id)`), *couldn't be built* (a 16 kB
      text key), *slower* (the `ORDER BY created LIMIT 10` misestimate) in the IT; on JOB,
      `movie_info (info)` → couldn't be built and 10c under `cast_info (movie_id)` → slower.
- [x] Dogfood: `pglens confirm` on the JOB copy with the JOB `.sql` files — **8 of 10** per-index
      verdicts match the benchmark; the 2 that differ sit near the 15 % line and move with run-to-run
      variation of JOB's heaviest unindexed plans (`docs/benchmarks.md`, measured, not assumed).
- [x] Log parsing tested on real PG16 log output (both logging modes, both protocols, named and
      unnamed statements, `NULL` parameters, quotes, multi-line, dollar-quotes).
- [x] Logged values and statement text never appear in the report or JSON (IT: a secret parameter
      value is absent; failures carry a SQLSTATE only).
- [x] Tests green on PG16 (full build) + PG17/18 (confirm IT); lint clean; docs + ADR-0042 updated.

## 6. Verification (self-check)
Take the JOB benchmark's worst case — 10c with `cast_info (movie_id)`, estimated −91 %, measured
0.93 s → 7.3 s — and run `pglens confirm` for it on a copy. It must say **slower**, with numbers
close to the benchmark's. If it says *faster*, the phase isn't done.

**Result (2026-09-25): passed** — `pglens confirm` reported 10c under `cast_info (movie_id)` as
**slower, 928.6 ms → 9,209.6 ms** (estimate −90.7 %).

## 7. Skipped, and the one resource per new skill
- Skipped: server/agent confirmations, cloud clone automation, `auto_explain` (B6a), CONCURRENTLY.
- Resource: PostgreSQL docs, *Error Reporting and Logging* (`log_min_duration_statement`,
  `log_parameter_max_length`) — read the "What to Log" section; skip CSV/JSON log formats for now.

Sources checked 2026-09-25: [PostgreSQL 16 logging config](https://www.postgresql.org/docs/16/runtime-config-logging.html),
[Neon — instant Postgres cloning](https://neon.com/faqs/postgres-instant-cloning-production-databases-testing),
[The Modern Way to Clone Postgres in 2026](https://dev.to/reeshee/the-modern-way-to-clone-postgres-in-2026-kmi).
