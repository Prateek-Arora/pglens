# PgLens

> Open-source, self-hosted Postgres **slow-query & index advisor** with **HypoPG-validated** index recommendations and **local-LLM-explained** query plans.

PgLens watches a Postgres database's `pg_stat_statements`, ranks the queries that actually cost you time, captures their `EXPLAIN` plans, and recommends indexes — then **proves each recommendation against the real planner with [HypoPG](https://github.com/HypoPG/hypopg)** so the "expected improvement" is a measured cost delta, not a guess. Everything runs on free/local infrastructure — **your data stays on your own infrastructure** (no query text is ever sent to a third-party service).

**Status:** 🟢 **Phase 2 shipped (`v0.0.2`) — the collector agent, server & time-series history.** A lightweight **`pglens-agent`** streams `pg_stat_statements` to a central **`pglens-server`** over **gRPC**; the server persists a **delta time-series**, runs the engine on a schedule with **HypoPG-validated** recommendations, produces **index-hygiene** advice (unused / duplicate indexes), and answers **trend / top-mover** questions. The one-shot **`pglens scan` CLI** (Phase 1, `v0.0.1`) is still here for a quick snapshot. Next: a local-LLM plan explainer (Phase 3) and a Next.js dashboard (Phase 4, the ship target) — see the [roadmap](docs/project.md).

## Design principles

- **No fabricated evidence.** Every number is real and labeled — a HypoPG cost *estimate* is shown as such, alongside the real `pg_stat_statements` time as the measured "before".
- **Safe by default.** The monitored database is opened **read-only** at two independent layers — a least-privilege login role with no write grant, plus a session-level `READ ONLY` guard — so writes are rejected by the database itself. Recommendations are validated with *hypothetical* indexes only (HypoPG) — nothing is ever created on your DB.
- **Deterministic core, optional AI.** Analysis is correct and complete with no LLM; the LLM (later) only phrases facts the core already owns.
- **HypoPG honesty.** btree/brin/hash/bloom recs are planner-validated with a real cost delta. GIN/GiST recs (jsonb, full-text, `LIKE '%…%'`) are surfaced but clearly labeled **"not planner-validated"** — HypoPG can't simulate them, and PgLens never pretends it did.

## Try it on your DB in 2 minutes

**Prerequisites:** Docker (with the Compose plugin), GNU Make, and a JDK (17 or newer) to run the Gradle wrapper — which then auto-provisions the JDK 21 the engine builds and runs on, so you don't have to install it separately. Start Docker first.

### 1. Spin up the bundled demo database (a deliberately-slow Postgres)

```bash
git clone <repo-url> pglens && cd pglens
make up        # build the jars + images and start the full stack (dbs + server + agent), waiting for health
make seed      # load skewed demo data (~1M rows) with deliberately-missing indexes
make warmup    # replay the slow-query pack so pg_stat_statements accumulates real stats
```

*(`make up` now brings up the whole Phase-2 stack. For the one-shot CLI scan below you only need the databases; the running collector→server system is covered in the **"Run it as a running system"** section further down.)*

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

## Run it as a running system (collector → server → history)

The `pglens scan` CLI is a one-shot snapshot. Phase 2 (`v0.0.2`) adds the real product shape: a lightweight **agent** that streams stats to a central **server**, which remembers them over time.

```bash
make up         # build jars + images and start the whole stack (dbs + server + agent), waiting for health
make register   # register the demo agent (its db-name + token) so the server accepts its samples
make seed       # load the skewed demo data
make warmup     # replay the slow-query pack so pg_stat_statements accumulates
```

Now the loop runs on its own. Every interval the agent — logged in as a read-only **`pglens_ro`** role — streams `pg_stat_statements` to the server, which stores a **delta time-series**, runs the engine on a schedule, hands HypoPG-validation work **back to the agent to run next to the database**, and records **validated recommendations**, **index-hygiene** findings, and **trends** in the metadata DB.

Watch it accumulate:

```bash
make psql-metadata   # open a shell on PgLens's own store, then e.g.:
#   SELECT count(*) FROM query_stats;          -- the growing delta time-series
#   SELECT ddl, status, before_cost, after_cost
#     FROM recommendations WHERE status = 'PLANNER_VALIDATED';
```

**Topology (`a-pull`).** The agent only ever dials **out** — it pushes samples up and *pulls* validation work down (short, retryable, load-balanceable gRPC calls; the server never connects into the agent). Deltas are computed **server-side**, anchored to the last persisted sample, so a lost send can't lose or double-count a window. The full REST/GraphQL API and dashboard are Phase 4; today the trends/recs are reached via SQL (above) and the integration tests. Details in [`docs/architecture.md`](docs/architecture.md); the *why* in [`docs/decisions.md`](docs/decisions.md) (ADR-0023…0034).

## The demo environment

`make help` lists every target. The four services:

| Service | Purpose | Host port |
|---|---|---|
| `monitored-db` | The database PgLens observes (demo data: `pglens_demo`) | `5433` |
| `metadata-db`  | PgLens's own store, pgvector (`pglens_meta`) | `5434` |
| `server` | The central brain: gRPC ingest/validation + scheduled analysis | `9090` |
| `agent` | The collector next to `monitored-db` (headless — no port) | — |

Default local-dev credentials are `pglens` / `pglens` (override via `.env`; see `.env.example`). `make test` runs the end-to-end reproducibility gate (also run in CI); `make bench` runs the dogfood index benchmark; `make psql-monitored` / `make psql-metadata` open a shell on either database.

`demo/slow_queries.sql` is a documented pack of known anti-patterns — the **ground-truth oracle** the engine is tested against.

## How it works

A pure **analysis engine** (`:engine`, no framework dependencies in its core) does the reasoning:

`pg_stat_statements` (ranked, hygiene-filtered) → `EXPLAIN (GENERIC_PLAN)` capture → anti-pattern rules → candidate `CREATE INDEX` → **HypoPG validation gate** (kept only if the planner *uses* the hypothetical index and cost drops past a threshold) → cross-query ranking.

The engine is split into a **pure half** (parse / detect / rank — no database) and an **I/O half** (reads Postgres, runs HypoPG). Phase 1's `:cli` runs both over one connection. **Phase 2 recomposes the same halves across two processes — no rewrite:** the I/O half runs on the **agent** (next to the monitored DB, read-only); the pure half runs on the **server** (over the persisted time-series). They talk over gRPC via a shared `:proto` contract, and the server owns the metadata schema (Flyway migrations), the scheduled singleton analysis job, the validation work-queue, index hygiene, and trends. PgLens even **dogfoods its own tuning** — it found a missing index on its own metadata schema and added a BRIN index on measured evidence (see [`docs/benchmarks.md`](docs/benchmarks.md)).

## Roadmap

Full plan and status live in [`docs/project.md`](docs/project.md); architecture in [`docs/architecture.md`](docs/architecture.md); the *why* behind each decision in [`docs/decisions.md`](docs/decisions.md).

## License

[Apache-2.0](LICENSE).
