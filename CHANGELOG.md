# Changelog

All notable changes to PgLens. Versions follow [SemVer](https://semver.org/); before 1.0 a minor
version may change the `--json` contract or the API (each change is listed here and versioned in
the contract itself). The *why* behind each change is in [`docs/decisions.md`](docs/decisions.md).

## [0.1.0-rc]

The first release candidate of the complete self-hosted tool: the web dashboard on top of the
agent, server, API and CLI.

### Added
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

### Changed
- The demo and metadata databases listen on `127.0.0.1` only (they use a default password).
- The dev TLS certificate also names `host.docker.internal`, and is re-issued when the requested
  names change — an agent on another machine then needs the new `ca.pem`.
- `--json` contract **1.5**: `planError` per query; table names are identities
  (schema-qualified unless `public`, quoted as Postgres would), columns are raw names.
- One version everywhere (`0.1.0-rc`), checked in CI.

### Upgrading from 0.0.7
Run `make up`: history carries over (migration V11). Copy the re-issued `ca.pem` to any agent on
another machine.

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
