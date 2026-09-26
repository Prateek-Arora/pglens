# PgLens

> Open-source, self-hosted Postgres **slow-query & index advisor** with **HypoPG-validated** index recommendations and **local-LLM-explained** query plans.

PgLens watches a Postgres database's `pg_stat_statements`, ranks the queries that actually cost you time, captures their `EXPLAIN` plans, and recommends indexes — then **checks each recommendation against the real planner with [HypoPG](https://github.com/HypoPG/hypopg)** so the "expected improvement" is the planner's own cost estimate for that index — not a guess, and always labeled as an estimate rather than a measured runtime. Everything runs on free/local infrastructure — **your data stays on your own infrastructure** (no query text is sent to a third-party service; the optional LLM explanations use a local model unless you explicitly allow a remote one).

**Status:** 🟢 **`v0.0.7` — Phases 0–3 and 4A shipped: the CLI engine, the collector agent + server + time-series history, a recommendation-accuracy pass measured on two public benchmarks, and a secured HTTP API** ([how accurate is it?](#how-accurate-is-it--check-before-you-apply)). A lightweight **`pglens-agent`** streams `pg_stat_statements` to a central **`pglens-server`** over **gRPC**; the server persists a **delta time-series**, runs the engine on a schedule with **HypoPG-validated** recommendations, produces **index-hygiene** advice (unused / duplicate indexes), and answers **trend / top-mover** questions. The one-shot **`pglens scan` CLI** (Phase 1, `v0.0.1`) is still here for a quick snapshot, **`pglens confirm`** (Phase 2.6, `v0.0.5`) measures its recommendations on a copy of your database before you apply them, **`--plain`** (Phase 3, `v0.0.6`) explains each recommended index in plain language, with an optional local LLM whose wording is checked against PgLens's facts, and the server's **[HTTP API](#the-http-api)** (Phase 4A, `v0.0.7`) serves all of it to scripts, behind logins and TLS. Next: the Next.js dashboard (Phase 4B, `v0.1.0-rc`) — see the [roadmap](docs/project.md).

## Design principles

- **No fabricated evidence.** Every number is real and labeled — a HypoPG cost *estimate* is shown as such, alongside the real `pg_stat_statements` time as the measured "before".
- **Safe by default.** The monitored database is opened **read-only** at two independent layers — a least-privilege login role with no write grant, plus a session-level `READ ONLY` guard — so writes are rejected by the database itself. Recommendations are validated with *hypothetical* indexes only (HypoPG) — nothing is ever created on your DB. The one command that builds real indexes, `pglens confirm`, never connects to it: it runs only on a **copy you explicitly mark** as scratch.
- **Deterministic core, optional AI.** Analysis is correct and complete with no LLM. The optional LLM (`--plain`) only phrases facts the core already owns. PgLens checks its words against those facts and falls back to its own template.
- **HypoPG honesty.** btree/brin/hash/bloom recs are planner-validated with a planner cost estimate. GIN/GiST recs (jsonb, full-text, `LIKE '%…%'`) are surfaced but clearly labeled **"not planner-validated"** — HypoPG can't simulate them, and PgLens never pretends it did.

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

**Value ranges.** A generic plan assumes an average parameter value, which can overstate the win on a skewed column. So for every validated index PgLens also re-plans the query with the column's real most-common and typical values from `pg_stats` and shows that range next to the estimate. On the demo, the join index below is −58.3 % generically but −0.0 % for the most common customer, so it gets a warning:

```
1. CREATE INDEX idx_order_items_order_id ON order_items (order_id);
     est. saves ~485 ms of query #6827925264069742854's 832.6 ms total  (−58.3% generic plan; −0.0% to −58.6% across sampled values, HypoPG planner estimates)
     ⚠ for some common values of orders.customer_id the planner expects little or no gain — the real win depends on which values your queries use
```

Ranking uses the generic estimate: `pg_stat_statements` doesn't record which values your queries use, and on the JOB benchmark the worst case was further from the measured result than the generic estimate on 6 of the 8 indexes where they differed ([ADR-0041](docs/decisions.md#adr-0041)). The sampled values never leave your database session — only their frequencies are reported. Each recommendation also shows HypoPG's size estimate for the index and each table's real read/write balance, and a B-tree on a column that may hold values too long to index gets a **build caution** with a one-line check. Every estimate is labeled as one, and nothing is ever built on your database.

### How accurate is it? — check before you apply

PgLens's recommendations have been measured, not just estimated: on two public benchmarks every recommended index was **built for real** on a throwaway copy and the workload's own queries were timed before and after ([docs/benchmarks.md](docs/benchmarks.md)).

| Benchmark | Recommendations that made their query ≥ 15 % faster | …that made it slower |
|---|---|---|
| TPC-H-derived (SF 0.1, uniform data) | 6 of 14 (43 %) | 4 of 14 |
| Join Order Benchmark on the real IMDB data (pre-registered) | 128 of 179 (72 %) — plus 34 whose index can't be built | 32 of 179 (18 %) |

The ranking itself holds up better than the individual percentages: the biggest estimated savings were the biggest real ones (on JOB, the #1 index saved 364 s of 424 s). But **"planner-validated" means the planner *estimates* a cheaper plan, not that the query will run faster** — when the planner misjudges row counts, a new index can make a query slower. So **try an index on a copy of your database and compare real timings before you rely on it** — `pglens confirm` does exactly that for you (next section).

### Check the indexes on a copy — `pglens confirm`

`pglens confirm` takes a scan report and a **copy** of your database, builds each recommended index there **for real**, times **your own statements** before and after, drops the index again, and gives a measured verdict per index: **faster** (warm time down ≥ 15 %), **no real effect**, **slower** (up ≥ 5 %), **couldn't be built**, or **not measured**. It never connects to the scanned database.

1. **Make a copy** — a [Neon branch](https://neon.com/docs/introduction/branching) or an Aurora clone (copy-on-write, seconds to minutes), an RDS snapshot restore, or `pg_dump | pg_restore`. It needs the same data (plans depend on it), `pg_stat_statements` loaded, and a user that owns the tables.
2. **Mark it as scratch** — on the copy, as its owner: `ALTER DATABASE shop_copy SET pglens.scratch = 'on';`. PgLens refuses any database without this marker (a `SET` or a connection option doesn't count), refuses the database the report came from, and refuses a standby.
3. **Give it your real statements** — a `.sql` file, or a PostgreSQL log with statements logged (`log_min_duration_statement = 250`, or `log_statement = 'all'` for a while). `pg_stat_statements` keeps no values, so PgLens needs statements the application really ran. It keeps only reads, and replays each one in a `READ ONLY` transaction that always rolls back.

```bash
pglens scan postgresql://ro@prod:5432/shop --json > scan.json
pglens confirm --report scan.json --copy postgresql://owner:pw@copy-host:5432/shop_copy --statements postgresql.log
```

On the bundled demo (a `pg_dump` copy, the demo's own logged workload):

```text
#1  CREATE INDEX ON order_items (order_id);
    NO REAL EFFECT  +0.2% measured (13.9 → 13.9 ms warm time over its statements)
      queryid 6827925264069742854   estimate −58.3%   measured +0.2%   NO REAL EFFECT
#3  CREATE INDEX ON orders (customer_id);
    FASTER  −28.1% measured (18.5 → 13.3 ms warm time over its statements)
      queryid 6827925264069742854   estimate −29.6%   measured −10.9%   NO REAL EFFECT
      queryid -4700021249033714155   estimate −98.7%   measured −80.2%   FASTER
…
Summary: 6 faster · 1 no real effect · 0 slower · 0 couldn't be built · 0 not measured
```

The #1 recommendation did nothing here: the logged workload asks for the demo's hottest customer, exactly the case its "−0.0 % for common values" caution warns about. Times are **measured on the copy** — different hardware, cache and load than production — and each index is measured only on the queries it was recommended for. Statement values stay on your machine: the report holds queryids, counts and times only. `--dry-run` shows what would be built and matched; `--json` emits the versioned `confirm` contract. Details and the safety design: [Phase 2.6](docs/phases/phase_2_6.md), [ADR-0042](docs/decisions.md#adr-0042).

### Plain-language explanations — `--plain`

Add `--plain` to `scan` (or `explain`) and PgLens explains its top indexes in plain language: why the query is slow, what the index changes, and the numbers behind it (how often the query ran and how long it took, the planner's estimated cost drop, and how many other queries the index helps). By default this is PgLens's own fixed wording, so every number is exact and no model is needed.

Add `--plain=llm` and a **local LLM** rewrites the three sentences from the same facts instead. PgLens itself still prints the index, the planner estimate and every caution, and checks the model's text before showing it:

```text
1. CREATE INDEX idx_orders_customer_id ON orders (customer_id);
   In short: PgLens recommends adding a B-tree index on the customer_id column in the orders table because the current plan has an estimated cost of 3,943.
   Why it's slow: The planner estimates that scanning all rows to find just eight matching ones costs 2,941.59 units of work.
   What the index changes: Creating this index would lower the estimated cost to 53 by allowing Postgres to locate the matching rows directly instead of reading the entire table.
   Estimate: Planner-validated (HypoPG estimate): total cost 3943 → 53 (−98.7%). Estimate from the planner, not a runtime measurement.
   (Written by qwen3.5:4b from PgLens's findings. Numbers, tables and the index were checked against them; the wording wasn't.)
```

**What is checked.** Every number in the model's answer, read with its unit ("~600k", "2.6 minutes", "58.3%"), must match one of PgLens's facts. Every table, column and index it names must be real, and the recommended index must be the only index it mentions. It must not write SQL, call a cost estimate a runtime, claim "N× faster", or give a percentage for an index the planner couldn't check. A failing answer is retried once. If it fails again, PgLens shows its own **template** explanation and says why; the template is also used when no model is running. The scan itself never depends on the model.

**Why the template is the default.** On 12 held-out real cases the model's answers passed every check with no false claims. But in a side-by-side ranking they read more smoothly while leaving out impact numbers (the measured runtime, how many other queries an index helps), and the template won 10–0. Full results: [`docs/llm-eval.md`](docs/llm-eval.md).

**Running a model.** PgLens talks to any **OpenAI-compatible** endpoint; the default is a local [Ollama](https://ollama.com) with `qwen3.5:4b` (Apache-2.0, 3.4 GB download, **~5 GB free RAM**).

| Runtime | How | Notes |
|---|---|---|
| Ollama, native (macOS / Windows / Linux) | install, then `ollama pull qwen3.5:4b` | **Best on a Mac**: it uses the GPU. The default `--llm-url` already points at it. |
| Ollama in Docker | `make llm-up` | Pulls the models into a volume. On macOS Docker has **no GPU**, so it runs on the CPU: ~13–35 s per explanation on an M3 Pro. On Linux with an NVIDIA GPU it's fast. |
| Docker Model Runner, llama.cpp `llama-server`, LM Studio, vLLM | `--llm-url http://host:port/v1 --llm-model <name>` | Any server that speaks `/v1/chat/completions`. For a thinking model, PgLens turns thinking off (`reasoning_effort: none`). |

Lower on RAM? `--llm-model qwen3.5:2b` (2.7 GB) works, but in PgLens's tests it invented facts more often; the checks then fall back to the template.

**Privacy.** The prompt holds `pg_stat_statements`' *normalized* query text (constants replaced by `$1`, `$2`, …), table and column names, the plan findings, planner costs and timings. It never holds sampled values or connection details. By default PgLens only sends it to **this machine or a private network**: loopback, private address ranges, `host.docker.internal`, or a Compose service name. It **refuses** any other endpoint unless you pass `--allow-remote-llm`, and then prints the host it sends to. With a local model, no query text reaches a third-party LLM service.

If you do allow a hosted API (set `PGLENS_LLM_API_KEY`), check its data terms first. As of 2026-09: Groq doesn't retain inference data by default, while Google's Gemini free tier may use your content to improve its products.

### 3. Point it at your own database

```bash
./gradlew :cli:bootRun --args="scan postgresql://user:password@your-host:5432/your_db"
```

Your database needs:

- **PostgreSQL 16 or newer** — PgLens plans normalized `pg_stat_statements` text with `EXPLAIN (GENERIC_PLAN)`, added in PG16. Older servers are refused up front with a clear message. (CI runs the integration suite on PG16; PG17 and PG18 are verified by a compatibility job.)
- **`pg_stat_statements` enabled** — add it to `shared_preload_libraries`, restart, then `CREATE EXTENSION pg_stat_statements;`
- **a login role with read access to stats** — e.g. `GRANT pg_read_all_stats TO <role>;`
- **`hypopg` (optional but recommended)** — `CREATE EXTENSION hypopg;` enables planner-validated cost deltas. Without it, PgLens still detects anti-patterns and suggests indexes, labeled *not planner-validated*.

PgLens connects **read-only** and never writes to the target — no index is ever built on it (`pglens confirm` builds them on a copy, never on the scanned database).

### Command reference

```bash
# scan: rank slow queries + recommend indexes
scan <conn> [--top N] [--min-calls N] [--order-by total|mean|calls] [--json]

# explain: drill into one statement by its pg_stat_statements queryid
explain <conn> <queryid>

# either, plus plain-language explanations of the top indexes (template by default; =llm for a local model, checked)
… --plain[=template|llm] [--plain-top N] [--llm-url URL] [--llm-model NAME] [--llm-timeout S] [--allow-remote-llm]

# confirm: build a scan's indexes on a marked scratch copy and time your real statements
confirm --report scan.json --copy <conn> --statements <file.sql|postgresql.log> [--statements …]
        [--top N] [--per-query N] [--runs N] [--statement-timeout S] [--dry-run] [--json]
```

`--json` emits the full report as a stable, versioned contract (handy for piping into other tools). `--min-calls` (default 20) ignores rarely-run statements; `--order-by` changes the ranking.

**Prefer a standalone binary?** Build the fat jar (needs a Java 21 runtime to *run*): `./gradlew :cli:bootJar` produces `cli/build/libs/cli-0.0.1-SNAPSHOT.jar`, then `java -jar cli/build/libs/cli-0.0.1-SNAPSHOT.jar scan <conn>`.

## Run it as a running system (collector → server → history)

The `pglens scan` CLI is a one-shot snapshot. Phase 2 (`v0.0.2`) adds the real product shape: a lightweight **agent** that streams stats to a central **server**, which remembers them over time.

```bash
make up         # build jars + images and start the whole stack; writes a generated admin password to deploy/compose/.env
make register   # register the demo db through the HTTP API; its agent token goes to deploy/compose/.env
make seed       # load the skewed demo data
make warmup     # replay the slow-query pack so pg_stat_statements accumulates
```

**Upgrading from `v0.0.6`:** your history carries over (the server migrates it on start). Run `make register` once after `make up`: the demo agent's old fixed token (`devtoken`) is no longer the default, so until then the agent is refused. `make register` gives it a fresh token.

Now the loop runs on its own. Every interval the agent — logged in as a read-only **`pglens_ro`** role — streams `pg_stat_statements` to the server, which stores a **delta time-series**, runs the engine on a schedule, hands HypoPG-validation work **back to the agent to run next to the database**, and records **validated recommendations**, **index-hygiene** findings, and **trends** in the metadata DB.

Watch it accumulate:

```bash
make psql-metadata   # open a shell on PgLens's own store, then e.g.:
#   SELECT count(*) FROM query_stats;          -- the growing delta time-series
#   SELECT ddl, status, before_cost, after_cost
#     FROM recommendations WHERE status = 'PLANNER_VALIDATED';
```

**Plain-language explanations on the server (optional).** With `PGLENS_EXPLAIN_ENABLED=true` (and `make llm-up`, or `PGLENS_LLM_URL=http://host.docker.internal:11434/v1` for native Ollama), the server explains each database's top indexes in the background. It uses the same checks and template fallback as `--plain`, and caches the results in the `explanations` table for the Phase 4 dashboard. It explains an index again only when its facts, the model or the prompt change. It also embeds a bundled set of PostgreSQL-docs passages in **pgvector** (HNSW index) for "further reading" links.

**Topology (`a-pull`).** The agent only ever dials **out** — it pushes samples up and *pulls* validation work down (short, retryable, load-balanceable gRPC calls; the server never connects into the agent). Deltas are computed **server-side**, anchored to the last persisted sample, so a lost send can't lose or double-count a window. A query's first sample only sets that anchor, so its activity shows up from its *next* run. Details in [`docs/architecture.md`](docs/architecture.md); the *why* in [`docs/decisions.md`](docs/decisions.md) (ADR-0023…0034, 0044).

### The HTTP API

Everything the server knows is served as JSON under `http://127.0.0.1:8080/api/v1` (Phase 4A, ADR-0044) — the leaderboard, one query's plan and findings, trends, recommendations, index hygiene, explanations. The OpenAPI description is at [`/api/v1/openapi.json`](http://127.0.0.1:8080/api/v1/openapi.json) (public; everything else needs a token).

| Endpoint | What it returns |
|---|---|
| `GET /databases` | registered databases, each with its agent's status (`CONNECTED` / `STALE` / `NEVER_CONNECTED`) |
| `GET /databases/{db}/queries?window=24h\|7d\|30d&sort=total\|mean\|calls&limit&offset` | the slowest queries in the window, with each one's best recommendation verdict |
| `GET /databases/{db}/queries/{queryid}` | the SQL, the **estimated** plan tree, findings pointing at plan nodes, recommendations, measured totals |
| `GET …/queries/{queryid}/trend?from&to&resolution=raw\|hour\|auto` | one point per interval (or per hour for long ranges); a missing interval is a gap, not a zero |
| `GET …/queries/{queryid}/explanation` | a plain-language explanation per recommended index — works with no LLM |
| `GET /databases/{db}/recommendations` · `/hygiene` · `/top-movers` · `/new-slow` | indexes to add (and ones HypoPG couldn't check), indexes to review for removal, what changed |
| `GET /recommendations` | the best indexes across every database |

Numbers are named for what they are: `measured…` values are summed `pg_stat_statements` deltas; `estimated…` and `plannerCost…` values are planner estimates. `queryid` is always a **string** (it's a signed 64-bit number — JavaScript would round it). Every recommendation comes with *planner-validated ≠ safe* and the `pglens confirm` command to measure it on a copy first. Windows start on a UTC hour (the response's `from` says exactly where); errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents.

**Use it from a script** with a read-only API token (the admin password is in `deploy/compose/.env` after `make up`):

```bash
API=http://127.0.0.1:8080/api/v1
PGLENS_ADMIN_PASSWORD=$(grep '^PGLENS_ADMIN_PASSWORD=' deploy/compose/.env | cut -d= -f2-)
# log in (a session lasts 8 h idle, 7 days at most), then mint a named, read-only token for the script
SESSION=$(curl -s $API/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PGLENS_ADMIN_PASSWORD\"}" | jq -r .token)
TOKEN=$(curl -s $API/api-tokens -H "Authorization: Bearer $SESSION" \
  -H 'Content-Type: application/json' -d '{"name":"nightly-report"}' | jq -r .token)   # shown once

# the 10 queries that cost the most time this week
curl -s "$API/databases/demo/queries?window=7d&limit=10" -H "Authorization: Bearer $TOKEN" \
  | jq -r '.items[] | [.queryid, .calls, .measuredTotalMs, .recommendation] | @tsv'
```

API tokens can read everything and change nothing; list or revoke them at `/api/v1/api-tokens`. Admins manage users at `/api/v1/users` (roles `ADMIN` / `VIEWER`) and databases at `/api/v1/databases` (`POST {"name": …}` returns the agent token **once**; `POST /databases/{db}/token` rotates it; `DELETE /databases/{db}?confirm={db}` deletes PgLens's history for it — never the database itself). Speed on a 30-day, 500-query history: every endpoint under 50 ms p95 (`make bench-api`, [`docs/benchmarks.md`](docs/benchmarks.md)).

### Security defaults

- **Agent ↔ server gRPC is TLS.** `make up` runs a one-shot `certs` service that creates a dev CA and a server certificate (for `server`, `localhost`, `127.0.0.1`) in the `grpc-certs` volume and throws the CA's key away; the agent trusts only that CA. For a real deployment give the server your own certificate (`PGLENS_GRPC_TLS_CERT`, `PGLENS_GRPC_TLS_KEY`) and the agent its CA (`PGLENS_SERVER_CA_CERT`). Plaintext needs `PGLENS_GRPC_PLAINTEXT=true` on the server *and* `PGLENS_SERVER_PLAINTEXT=true` on the agent, and is logged as a warning.
- **The HTTP API needs a login.** The first admin's password comes from `PGLENS_ADMIN_PASSWORD` (12+ characters), otherwise a random one is logged once on first start (`docker compose logs server`). Passwords are stored as bcrypt hashes and tokens as SHA-256 hashes; 5 failed logins lock a username out of new logins for 15 minutes. `PGLENS_AUTH_MODE=none` turns logins off — only for one person on their own machine; every response then carries `X-PgLens-Auth: none`.
- **The API listens on `127.0.0.1` only** in compose. To reach it from elsewhere, put HTTPS in front — e.g. [Caddy](https://caddyserver.com), which gets a certificate automatically:
  ```
  pglens.example.com {
      reverse_proxy 127.0.0.1:8080
  }
  ```

**An agent on another machine.** Run the agent image next to your database with `PGLENS_MONITORED_DB_URL` (a read-only role, like the demo's [`20_pglens_ro.sql`](deploy/compose/monitored/initdb/20_pglens_ro.sql); the database needs what [step 3](#3-point-it-at-your-own-database) lists), `PGLENS_DB_NAME` and `PGLENS_AGENT_TOKEN` (from `POST /api/v1/databases`), `PGLENS_SERVER_HOST` / `PGLENS_SERVER_PORT`, and `PGLENS_SERVER_CA_CERT` pointing at the CA certificate (from the dev setup: `docker compose -f deploy/compose/docker-compose.yml exec agent cat /certs/ca.pem > ca.pem`). The server's certificate must name the host the agent dials: set `PGLENS_TLS_EXTRA_SANS=DNS:pglens.internal` (or `IP:10.0.0.5`) before the certificates are first made — they are kept while valid, so to add a name later remove the `grpc-certs` volume and `make up` again.

## The demo environment

`make help` lists every target. The four services:

| Service | Purpose | Host port |
|---|---|---|
| `monitored-db` | The database PgLens observes (demo data: `pglens_demo`) | `5433` |
| `metadata-db`  | PgLens's own store, pgvector (`pglens_meta`) | `5434` |
| `server` | The central brain: gRPC ingest/validation (TLS), scheduled analysis, the HTTP API | `9090` (gRPC), `127.0.0.1:8080` (HTTP) |
| `agent` | The collector next to `monitored-db` (headless — no port) | — |
| `certs` | One-shot: creates the dev TLS certificates for gRPC, then exits | — |

Default local-dev credentials are `pglens` / `pglens` (override via `deploy/compose/.env`; see `deploy/compose/.env.example`). `make test` runs the end-to-end reproducibility gate (also run in CI); `make bench` runs the dogfood index benchmark; `make bench-api` the API latency benchmark; `make psql-monitored` / `make psql-metadata` open a shell on either database.

`demo/slow_queries.sql` is a documented pack of known anti-patterns — the **ground-truth oracle** the engine is tested against.

## How it works

A pure **analysis engine** (`:engine`, no framework dependencies in its core) does the reasoning:

`pg_stat_statements` (ranked, hygiene-filtered) → `EXPLAIN (GENERIC_PLAN)` capture → anti-pattern rules → candidate `CREATE INDEX` → **HypoPG validation gate** (kept only if the planner *uses* the hypothetical index and cost drops past a threshold) → cross-query ranking.

The engine is split into a **pure half** (parse / detect / rank — no database) and an **I/O half** (reads Postgres, runs HypoPG). Phase 1's `:cli` runs both over one connection. **Phase 2 recomposes the same halves across two processes — no rewrite:** the I/O half runs on the **agent** (next to the monitored DB, read-only); the pure half runs on the **server** (over the persisted time-series). They talk over gRPC via a shared `:proto` contract, and the server owns the metadata schema (Flyway migrations), the scheduled singleton analysis job, the validation work-queue, index hygiene, trends and the HTTP API. PgLens even **dogfoods its own tuning** — it found a missing index on its own metadata schema and added a BRIN index on measured evidence, and when its own API was too slow on a 30-day history (741 ms), an hourly rollup brought it to 44 ms (see [`docs/benchmarks.md`](docs/benchmarks.md)).

## Roadmap

Full plan and status live in [`docs/project.md`](docs/project.md); architecture in [`docs/architecture.md`](docs/architecture.md); the *why* behind each decision in [`docs/decisions.md`](docs/decisions.md).

## License

[Apache-2.0](LICENSE).
