# The `pglens` CLI

`pglens scan` ranks a database's costliest queries and recommends indexes in one run, with no server. Run it from the release image, the release jar (needs Java 21), or source:

```bash
# Docker (no install); -v lets scan/confirm read and write report files in this folder
alias pglens='docker run --rm -it -v "$PWD:/work" ghcr.io/prateek-arora/pglens-cli:0.1.0-rc'
# or: java -jar pglens-cli-0.1.0-rc.jar …   (from the GitHub release)
# or, from a clone: ./gradlew :cli:bootRun --args="…"

pglens scan postgresql://pglens_ro:<password>@your-host:5432/your_db
```

From a container, a database on your own machine is `host.docker.internal` (add `--add-host=host.docker.internal:host-gateway` on Linux). To try it on the bundled demo after the [quickstart](../README.md#quickstart): `pglens scan postgresql://pglens:pglens@host.docker.internal:5433/pglens_demo`.

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

The `CREATE INDEX` runs as written: tables outside `public` are schema-qualified (`ON billing.invoices (…)`), names are quoted when Postgres needs it (`ON "Post" ("authorId")`), and a partitioned table gets one index on the parent, which covers every partition.

Legitimate full scans get **no** recommendation, and jsonb/GIN cases are shown as `[not planner-validated]` — never with a fabricated number.

**Value ranges.** A generic plan assumes an average parameter value, which can overstate the win on a skewed column. So for every validated index PgLens also re-plans the query with the column's real most-common and typical values from `pg_stats` and shows that range next to the estimate. On the demo, the join index below is −58.3 % generically but −0.0 % for the most common customer, so it gets a warning:

```
1. CREATE INDEX idx_order_items_order_id ON order_items (order_id);
     est. saves ~485 ms of query #6827925264069742854's 832.6 ms total  (−58.3% generic plan; −0.0% to −58.6% across sampled values, HypoPG planner estimates)
     ⚠ for some common values of orders.customer_id the planner expects little or no gain — the real win depends on which values your queries use
```

Ranking uses the generic estimate: `pg_stat_statements` doesn't record which values your queries use, and on the JOB benchmark the worst case was further from the measured result than the generic estimate on 6 of the 8 indexes where they differed ([ADR-0041](decisions.md#adr-0041)). The sampled values never leave your database session — only their frequencies are reported. Each recommendation also shows HypoPG's size estimate for the index and each table's real read/write balance, and a B-tree on a column that may hold values too long to index gets a **build caution** with a one-line check. Every estimate is labeled as one, and nothing is ever built on your database.

## How accurate is it? — check before you apply

PgLens's recommendations have been measured, not just estimated: on two public benchmarks every recommended index was **built for real** on a throwaway copy and the workload's own queries were timed before and after ([docs/benchmarks.md](benchmarks.md)).

| Benchmark | Recommendations that made their query ≥ 15 % faster | …that made it slower |
|---|---|---|
| TPC-H-derived (SF 0.1, uniform data) | 6 of 14 (43 %) | 4 of 14 |
| Join Order Benchmark on the real IMDB data (pre-registered) | 128 of 179 (72 %) — plus 34 whose index can't be built | 32 of 179 (18 %) |

The ranking itself holds up better than the individual percentages: the biggest estimated savings were the biggest real ones (on JOB, the #1 index saved 364 s of 424 s). But **"planner-validated" means the planner *estimates* a cheaper plan, not that the query will run faster** — when the planner misjudges row counts, a new index can make a query slower. So **try an index on a copy of your database and compare real timings before you rely on it** — `pglens confirm` does exactly that for you (next section).

## Check the indexes on a copy — `pglens confirm`

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

The #1 recommendation did nothing here: the logged workload asks for the demo's hottest customer, exactly the case its "−0.0 % for common values" caution warns about. Times are **measured on the copy** — different hardware, cache and load than production — and each index is measured only on the queries it was recommended for. Statement values stay on your machine: the report holds queryids, counts and times only. `--dry-run` shows what would be built and matched; `--json` emits the versioned `confirm` contract. Details and the safety design: [Phase 2.6](phases/phase_2_6.md), [ADR-0042](decisions.md#adr-0042).

## Plain-language explanations — `--plain`

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

**Why the template is the default.** On 12 held-out real cases the model's answers passed every check with no false claims. But in a side-by-side ranking they read more smoothly while leaving out impact numbers (the measured runtime, how many other queries an index helps), and the template won 10–0. Full results: [`llm-eval.md`](llm-eval.md).

**Running a model.** PgLens talks to any **OpenAI-compatible** endpoint; the default is a local [Ollama](https://ollama.com) with `qwen3.5:4b` (Apache-2.0, 3.4 GB download, **~5 GB free RAM**).

| Runtime | How | Notes |
|---|---|---|
| Ollama, native (macOS / Windows / Linux) | install, then `ollama pull qwen3.5:4b` | **Best on a Mac**: it uses the GPU. The default `--llm-url` already points at it. |
| Ollama in Docker | `make llm-up` | Pulls the models into a volume. On macOS Docker has **no GPU**, so it runs on the CPU: ~13–35 s per explanation on an M3 Pro. On Linux with an NVIDIA GPU it's fast. |
| Docker Model Runner, llama.cpp `llama-server`, LM Studio, vLLM | `--llm-url http://host:port/v1 --llm-model <name>` | Any server that speaks `/v1/chat/completions`. For a thinking model, PgLens turns thinking off (`reasoning_effort: none`). |

Lower on RAM? `--llm-model qwen3.5:2b` (2.7 GB) works, but in PgLens's tests it invented facts more often; the checks then fall back to the template.

**Privacy.** The prompt holds `pg_stat_statements`' *normalized* query text (constants replaced by `$1`, `$2`, …), table and column names, the plan findings, planner costs and timings. It never holds sampled values or connection details. By default PgLens only sends it to **this machine or a private network**: loopback, private address ranges, `host.docker.internal`, or a Compose service name. It **refuses** any other endpoint unless you pass `--allow-remote-llm`, and then prints the host it sends to. With a local model, no query text reaches a third-party LLM service.

If you do allow a hosted API (set `PGLENS_LLM_API_KEY`), check its data terms first. As of 2026-09: Groq doesn't retain inference data by default, while Google's Gemini free tier may use your content to improve its products.

## Command reference

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

**Building the jar yourself:** `./gradlew :cli:bootJar` (a JDK 17+ runs the Gradle wrapper, which provisions JDK 21) produces `cli/build/libs/cli-0.1.0-rc.jar`; run it with `java -jar` on Java 21.
