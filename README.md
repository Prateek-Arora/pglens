# PgLens

> Open-source, self-hosted Postgres **slow-query & index advisor** with **HypoPG-validated** index recommendations and **local-LLM-explained** query plans.

PgLens watches a Postgres database's `pg_stat_statements`, ranks the queries that actually cost you time, captures their `EXPLAIN` plans, and recommends indexes — then **proves each recommendation against the real planner with [HypoPG](https://github.com/HypoPG/hypopg)** so the "expected improvement" is a measured cost delta, not a guess. Everything runs on free/local infrastructure — **query text never leaves your machine.**

**Status:** 🟢 **Phase 1 — the analysis engine (CLI).** `pglens scan` is here: it ranks slow queries, captures plans, detects anti-patterns, and prints HypoPG-validated index recommendations (human-readable or `--json`). A local-LLM plan explainer (Phase 3) and a Next.js dashboard (Phase 4) come next — see the [roadmap](docs/project.md#phase-tracker). The ship target is Phase 4 (a self-hostable web tool).

## Design principles

- **No fabricated evidence.** Every number is real and labeled — a HypoPG cost *estimate* is shown as such, alongside the real `pg_stat_statements` time as the measured "before".
- **Safe by default.** The monitored database is opened **read-only** (writes are rejected by the database itself); recommendations are validated with *hypothetical* indexes only — nothing is ever created on your DB.
- **Deterministic core, optional AI.** Analysis is correct and complete with no LLM; the LLM (later) only phrases facts the core already owns.
- **HypoPG honesty.** btree/brin/hash/bloom recs are planner-validated with a real cost delta. GIN/GiST recs (jsonb, full-text, `LIKE '%…%'`) are surfaced but clearly labeled **"not planner-validated"** — HypoPG can't simulate them, and PgLens never pretends it did.

## Try it on your DB in 2 minutes

**Prerequisites:** Docker (with the Compose plugin), GNU Make, and a JDK (17 or newer) to run the Gradle wrapper — which then auto-provisions the JDK 21 the engine builds and runs on, so you don't have to install it separately. Start Docker first.

### 1. Spin up the bundled demo database (a deliberately-slow Postgres)

```bash
git clone <repo-url> pglens && cd pglens
make up        # start the monitored (pg_stat_statements + hypopg) and metadata (pgvector) databases
make seed      # load skewed demo data (~1M rows) with deliberately-missing indexes
make warmup    # replay the slow-query pack so pg_stat_statements accumulates real stats
```

### 2. Scan it

```bash
./gradlew :cli:bootRun --args="scan postgresql://pglens:pglens@localhost:5433/pglens_demo"
```

You'll get a ranked report of the costliest queries, each with the offending plan node, the anti-pattern found, and a copy-pasteable `CREATE INDEX` with its **HypoPG planner-cost delta** — for example:

```
#1  total 331.2 ms   mean 13.2 ms   calls 25   queryid 6827925264069742854
    SELECT o.id, o.total_cents, count(oi.id) AS items FROM orders o JOIN order_items oi ...
    findings:
      • [R3] Unindexed join key — HIGH
          Join on order_items.order_id has no index, so the order_items side is scanned (~500000 rows).
    recommendations:
      [validated] CREATE INDEX idx_order_items_order_id ON order_items (order_id);
          Planner-validated (HypoPG estimate): total cost 9761 → 4071 (−58.3%). Estimate from the planner, not a runtime measurement.
```

Legitimate full scans get **no** recommendation, and jsonb/GIN cases are shown as `[not planner-validated]` — never with a fabricated number.

### 3. Point it at your own database

```bash
./gradlew :cli:bootRun --args="scan postgresql://user:password@your-host:5432/your_db"
```

Your database needs:

- **`pg_stat_statements` enabled** — add it to `shared_preload_libraries`, restart, then `CREATE EXTENSION pg_stat_statements;`
- **a login role with read access to stats** — e.g. `GRANT pg_read_all_stats TO <role>;`
- **`hypopg` (optional but recommended)** — `CREATE EXTENSION hypopg;` enables planner-validated cost deltas. Without it, PgLens still detects anti-patterns and suggests indexes, labeled *not planner-validated*.

PgLens connects **read-only** and never writes to the target — no index is ever built on it.

### Command reference

```bash
# scan: rank slow queries + recommend indexes
scan <conn> [--top N] [--min-calls N] [--order-by total|mean|calls] [--json]

# explain: drill into one statement by its pg_stat_statements queryid
explain <conn> <queryid>
```

`--json` emits the full report as a stable, versioned contract (handy for piping into other tools). `--min-calls` (default 20) ignores rarely-run statements; `--order-by` changes the ranking.

**Prefer a standalone binary?** Build the fat jar (needs a Java 21 runtime to *run*): `./gradlew :cli:bootJar` produces `cli/build/libs/cli-0.0.1-SNAPSHOT.jar`, then `java -jar cli/build/libs/cli-0.0.1-SNAPSHOT.jar scan <conn>`.

## The demo environment

`make help` lists every target. The two databases:

| Database | Purpose | Host port | DB name |
|---|---|---|---|
| `monitored-db` | The database PgLens observes (demo data) | `5433` | `pglens_demo` |
| `metadata-db`  | PgLens's own store (pgvector) | `5434` | `pglens_meta` |

Default local-dev credentials are `pglens` / `pglens` (override via `.env`; see `.env.example`). `make test` runs the end-to-end reproducibility gate (also run in CI); `make psql-monitored` opens a shell on the demo DB.

`demo/slow_queries.sql` is a documented pack of known anti-patterns — the **ground-truth oracle** the engine is tested against.

## How it works

A pure **analysis engine** (`:engine`, no framework dependencies in its core) does the work, wired to Postgres over a single read-only connection:

`pg_stat_statements` (ranked, hygiene-filtered) → `EXPLAIN (GENERIC_PLAN)` capture → anti-pattern rules → candidate `CREATE INDEX` → **HypoPG validation gate** (kept only if the planner *uses* the hypothetical index and cost drops past a threshold) → cross-query ranking. The `:cli` module is a thin Spring Boot + picocli front end over that engine, so Phase 2's collector agent and server can reuse the same core unchanged.

## Roadmap

Full plan and status live in [`docs/project.md`](docs/project.md); architecture in [`docs/architecture.md`](docs/architecture.md); the *why* behind each decision in [`docs/decisions.md`](docs/decisions.md).

## License

[Apache-2.0](LICENSE).
