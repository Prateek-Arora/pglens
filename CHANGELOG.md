# Changelog

All notable changes to PgLens. Versions follow [SemVer](https://semver.org/); before 1.0 a minor
version may change the `--json` contract or the API (each change is listed here and versioned in
the contract itself). The *why* behind each change is in [`docs/decisions.md`](docs/decisions.md).

## [0.1.0-rc]

The first release candidate of the complete self-hosted tool: the web dashboard on top of the
agent, server, API and CLI.

### Added
- **Overview** — the dashboard now opens on the impact across every database: measured query time,
  how much of it is in queries with an index to try, the planner-estimated saving (hatched, marked
  *est.*), what to fix first, an hourly chart, and indexes that were built with their measured
  result. Charts on each database's slow-query and trends pages, with the busiest queries hour by
  hour; each leaderboard row shows its estimated saving. API: `GET /overview`,
  `/databases/{db}/timeline`, `/databases/{db}/applied`, `/system`.
- **Built and measured** — when an index PgLens recommended appears on the database (any name, as
  long as it serves the recommendation), the advice retires and PgLens compares each query's
  **measured** mean time per call in the 7 days before against the time since, split at the sample
  the index appeared in, once both sides have 10 calls (ADR-0051).
- **Settings → Plain-language explanations** shows whether the template or a model writes them, the
  model and its host, and how its last pass went; *About* shows the server's version.
- **`make up-no-demo`** and the compose profile `demo`: plain `docker compose up` now starts PgLens
  alone, for your own databases. [docs/operations.md](docs/operations.md) covers managed Postgres,
  poolers, TLS, your own LLM, disk, backups and upgrades.
- **Materialized views** are analysed like tables, with their existing indexes.
- **Retention**: per-sample rows older than 35 days (`PGLENS_RETENTION_RAW_DAYS`) are deleted
  hourly; the hourly rollup is kept (B26).
- **Web dashboard** (Next.js): slow queries by measured time, a query page with the suggested
  index, how to measure it on a copy, the redlined plan, a plain-language explanation and the
  measured trend; recommendations across databases with index hygiene; trends; settings for
  users, API tokens and databases. Light and dark themes; keyboard stepping between queries.
- **Released images and CLI**: `ghcr.io/prateek-arora/pglens-{server,agent,cli,dashboard,demo-db}`
  (amd64 + arm64) and a CLI jar on the GitHub release. `docker compose up` pulls them; `make up`
  builds everything from source with only Docker (no JDK needed).
- **Access diagnostics**: a query whose plan can't be captured says why (e.g. `permission denied
  for schema app`) in the CLI, the API (`planUnavailableReason`) and the dashboard; the CLI notes
  and the agent's log name the schemas the role can't read with the exact `GRANT`s; the agent
  picks the plans up once access is granted, without a restart.

### Fixed
- **Tables outside `public`** got a `CREATE INDEX` without the schema, so HypoPG rejected every
  candidate and the SQL failed when pasted. Tables are now schema-qualified.
- **Quoted and mixed-case names** (`"Post"."authorId"`, as Prisma creates them) and **`varchar`
  columns** (compared as `(col)::text`) got no findings at all. Both are now read, and the SQL
  quotes names exactly as Postgres needs.
- **Partitioned tables** got one index per partition; they now get one index on the parent, which
  covers every partition (HypoPG checks it on the parent).
- An agent that couldn't reach the server logged "server rejected the sample batch"; it now says
  what failed (e.g. the TLS certificate doesn't name the host) and how to fix it.
- The dashboard said "nothing an index would fix" when PgLens's rules simply didn't match; it now
  says which patterns it checks and that expression and partial indexes aren't suggested yet.

- **PgLens could make an application's writes fail behind a connection pooler.** Its read-only guard
  was a session `SET`; through a transaction-mode pooler (PgBouncer, Supabase port 6543, Neon
  `-pooler`) it stayed on a shared server connection and made the next client's transactions
  read-only (reproduced on PgBouncer 1.24). The guards are now connection startup options, which a
  pooler refuses, and PgLens stops with a message saying to connect directly (ADR-0053).
- **Advice never went away.** Recommendations were only ever added: an index you built, a changed
  plan, or an older engine's format stayed on the dashboard for good (it showed the same index
  twice, and indexes on system catalogs). The analysis now retires advice the engine no longer
  proposes, and plans are captured again when the database's indexes change (and hourly).
- **Indexes on system catalogs** (from `pg_dump`'s own queries) are no longer proposed (B27).
- A hosted embedding model with vectors of another size re-embedded the whole docs corpus every
  pass; it is now detected once and the docs links are turned off.

### Changed
- The dashboard's home page is the overview; the header's first link is *Overview*.
- The Ollama service's port listens on `127.0.0.1` only.
- The demo and metadata databases listen on `127.0.0.1` only (they use a default password).
- The dev TLS certificate also names `host.docker.internal`, and is re-issued when the requested
  names change — an agent on another machine then needs the new `ca.pem`.
- `--json` contract **1.5**: `planError` per query; table names are identities
  (schema-qualified unless `public`, quoted as Postgres would), columns are raw names.
- One version everywhere (`0.1.0-rc`), checked in CI.

### Upgrading from 0.0.7
Run `make up`: history carries over (migrations V11–V14; the first analysis pass then removes
stale and system-catalog advice). Copy the re-issued `ca.pem` to any agent on another machine.
Plain `docker compose up` no longer starts the demo database and agent — add `--profile demo`, or
use `make up`.

## [0.0.7] — 2026-09-26
Spring Boot 4.1; TLS between agent and server; the HTTP API with logins, read-only API tokens and
database registration.

## [0.0.6] — 2026-09-26
Plain-language explanations (`--plain`): PgLens's own template by default, a checked local LLM on
request.

## [0.0.5] — 2026-09-26
`pglens confirm`: build the recommended indexes on a marked copy and time your real statements.

## [0.0.4] — 2026-09-24
Accuracy sprint: value ranges from `pg_stats`, index size and write load, coverage checks, and two
public accuracy benchmarks (TPC-H-derived, JOB).

## [0.0.3] — 2026-08-30
Hardening of the collector/server loop.

## [0.0.2] — 2026-08-30
The collector agent streaming to a server over gRPC, with a time-series history, trends and index
hygiene.

## [0.0.1]
`pglens scan`: slow queries, plans, anti-patterns and HypoPG-validated index recommendations.
