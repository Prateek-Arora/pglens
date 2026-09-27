# PgLens

**Find the Postgres queries that cost you the most time, the index that would fix each one — and
whether it really did.** Open source, self-hosted, and private: it runs next to your databases and
nothing leaves them.

[![CI](https://github.com/Prateek-Arora/pglens/actions/workflows/ci.yml/badge.svg)](https://github.com/Prateek-Arora/pglens/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![PostgreSQL 16 | 17 | 18](https://img.shields.io/badge/PostgreSQL-16%20%7C%2017%20%7C%2018-336791)

![The PgLens overview: measured query time per hour, the share of it in queries with an index to try, the planner-estimated saving, what to fix first, and an index that was built with its measured before and after](docs/images/overview.png)

A read-only agent streams `pg_stat_statements` from each database to the PgLens server, which:

- **ranks where the time goes** — every query's measured total, mean and calls, over 24 h, 7 days or
  30 days, with trends and new slow queries;
- **reads each query's plan and redlines the problem** — the sequential scan on a selective filter,
  the unindexed join key, the sort that feeds a `LIMIT`;
- **suggests the index and checks it against your own planner** with
  [HypoPG](https://github.com/HypoPG/hypopg) hypothetical indexes: only indexes the planner would
  use, with its estimated cost drop, as copy-paste `CREATE INDEX` statements that work on your
  schemas, quoted names and partitions;
- **tells you to measure before you build** — `pglens confirm` times each index on a copy of the
  database, against your own statements;
- **notices when you build one and measures what it changed** — the advice retires, and the
  query's measured time per call before and after appears on the overview;
- **explains it in plain language** — from its own template, or any local or hosted LLM you point
  it at, with every number checked against PgLens's facts.

Every number says what it is: **measured** times come from your database; **estimates** come from
the planner, are marked *est.* and hatched, and are never presented as a speedup.

## Quickstart

You need Docker (with Compose) and GNU Make. This runs PgLens with a deliberately slow demo
database:

```bash
git clone https://github.com/Prateek-Arora/pglens.git && cd pglens
make up         # build and start: PgLens, its store, the demo database and its agent
make register   # register the demo database and hand its agent a token
make seed       # load the demo data
make warmup     # run the slow-query pack (run it again a minute later: a first sample only sets a baseline)
grep PGLENS_ADMIN_PASSWORD deploy/compose/.env   # your login
```

Open **<http://localhost:3000>** and sign in as `admin`. From a fresh clone with Docker's caches
warm, the dashboard showed the demo's slow queries and checked indexes about three minutes after
`git clone` (measured 2026-09-27); a first-ever run also downloads the base images and builds the
Java images.

To see the whole loop, build one of the suggested indexes on the demo and run the workload again:
`make psql-monitored`, then `CREATE INDEX ON orders (customer_id);`, and `make warmup` again. Within
a minute PgLens retires the advice, and *Built and measured* shows each query's measured time per
call before and after.

## Watch your own databases

```bash
make up-no-demo                                     # PgLens alone, built from source
docker compose -f deploy/compose/docker-compose.yml up -d   # …or the released images
```

1. **Prepare the database** — PostgreSQL 16+, `pg_stat_statements`, ideally `hypopg`, and a
   read-only role that can read the tables ([the SQL](docs/operations.md#2-prepare-each-database)).
2. **Add it in the dashboard** — *Overview → Add a database* shows the agent's token once, with a
   ready-to-fill config and the `docker run` command.
3. **Start the agent next to it** — it only dials out to the server's gRPC port (TLS), and never
   writes to your database.

Managed Postgres (RDS, Cloud SQL, Azure, Neon, Supabase), connection poolers, TLS and HTTPS, your
own LLM, disk use, backups and upgrades: **[docs/operations.md](docs/operations.md)**.

<table>
<tr>
<td width="50%"><img src="docs/images/query.png" alt="A query page: the suggested index with the planner's estimated cost drop, the command to measure it on a copy, and the plan with its problem nodes redlined"></td>
<td width="50%"><img src="docs/images/recommendations.png" alt="Recommendations: indexes to build ranked by estimated time saved, each with the queries it helps, and indexes that were built with their measured result"></td>
</tr>
<tr>
<td>The suggested index, how to measure it first, and the plan with its problems redlined.</td>
<td>What to build, biggest estimated saving first — and what building it really did.</td>
</tr>
</table>

## How accurate is it?

"Planner-validated" means the planner *estimates* a cheaper plan with the index — not that the query
will run faster. On two public benchmarks every recommended index was **built for real** on a copy
and the workload's own queries were timed before and after
([docs/benchmarks.md](docs/benchmarks.md)):

| Benchmark | Recommendations that made their query ≥ 15 % faster | …that made it slower |
|---|---|---|
| TPC-H-derived (SF 0.1, uniform data) | 6 of 14 (43 %) | 4 of 14 |
| Join Order Benchmark on the real IMDB data (pre-registered) | 128 of 179 (72 %) — plus 34 whose index can't be built | 32 of 179 (18 %) |

![179 planner-checked index recommendations on the Join Order Benchmark, each built and timed: 128 made the query at least 15 % faster, 19 made no clear change, and 32 made it at least 5 % slower, 7 of them more than twice as slow](docs/images/job-benchmark.png)

The ranking holds up better than the individual percentages: on JOB the #1 index saved 364 s of
424 s. But when the planner misjudges row counts a new index can make a query slower, so PgLens puts
a way to **measure first** next to every suggestion — `pglens confirm` builds each index on a copy
you mark as scratch and times your real statements — and **measures again after** you build it.

## Related tools

PgLens isn't the first tool to check index advice with HypoPG. [Dexter](https://github.com/ankane/dexter)
is an automatic indexer that can create the indexes itself; [PoWA](https://powa.readthedocs.io/) is a
workload analyzer with index suggestions (it needs `pg_qualstats`); [pganalyze](https://pganalyze.com/)
is a hosted commercial service with an index advisor; [Postgres MCP Pro](https://github.com/crystaldba/postgres-mcp)
gives AI agents index tuning and health checks; Supabase's
[index_advisor](https://github.com/supabase/index_advisor) suggests indexes for a single query.
PgLens's focus is the whole loop on your own infrastructure: history, planner-checked advice, a way to
measure first, and a measured before and after once the index exists.

## Safe by default

- **Read-only, twice.** The agent logs in as a role with no write grant, and its connection is
  read-only from the moment it opens — so the database itself refuses a write. Indexes are only ever
  *hypothetical* (HypoPG, gone at the end of the session). A connection pooler that would share
  session settings with your application is detected and refused.
- **Private.** Query text is `pg_stat_statements`' normalized form (`$1`); sampled values never leave
  the database session. An LLM endpoint outside this machine or network is refused unless you allow
  it.
- **Locked down.** Logins with bcrypt-hashed passwords and read-only API tokens; agent ↔ server gRPC
  over TLS; the dashboard, API and databases listen on `127.0.0.1` until you put HTTPS in front.
  See [SECURITY.md](SECURITY.md).
- **Works with no AI.** Every analysis is deterministic; the optional model only rewords facts PgLens
  already owns, and its answer is checked before it is shown.

## More

- **[The `pglens` CLI](docs/cli.md)** — a one-off `scan` with no server, `confirm` on a copy, and
  `--plain` explanations; from the release image or jar.
- **[The HTTP API](docs/api.md)** — everything the dashboard shows, as JSON with read-only tokens
  ([OpenAPI](docs/api/openapi.json)).
- **[Operations](docs/operations.md)** — running it for real.
- **How it works** — a pure analysis engine (`:engine`) runs on the CLI, and split across the agent
  (reads Postgres, runs HypoPG next to it) and the server (history, analysis, API) over gRPC; the
  dashboard is Next.js. [Architecture](docs/architecture.md) · [the *why* of each
  decision](docs/decisions.md) · [status](docs/project.md) · [changelog](CHANGELOG.md).

`make help` lists every development target; [CONTRIBUTING.md](CONTRIBUTING.md) has the workflow.

## License

[Apache-2.0](LICENSE).
