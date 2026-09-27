# Security policy

## Supported versions

Only the latest release gets security fixes while PgLens is pre-1.0.

## Reporting a vulnerability

Please report it privately through GitHub: **Security → Report a vulnerability** on this
repository. Don't open a public issue. Expect an answer within a week; a fix and a release follow
as soon as one is ready, and the report is credited unless you'd rather not.

## What PgLens promises (and where to look first)

- **The monitored database is never written to.** The agent and the CLI connect as a read-only
  role and set `READ ONLY` on the session; HypoPG indexes are hypothetical and session-local.
  Only `pglens confirm` writes, and only `CREATE`/`DROP INDEX` on a copy its owner marked with
  `ALTER DATABASE … SET pglens.scratch = 'on'`.
- **No query values leave your database session.** `pg_stat_statements` text is normalized
  (`$1`); sampled values used for estimates stay in the session. An LLM endpoint outside this
  machine or private network is refused unless explicitly allowed.
- **Logins by default.** Passwords are bcrypt hashes, tokens SHA-256 hashes; agent ↔ server gRPC is
  TLS; the dashboard, the HTTP API and both databases listen on `127.0.0.1` in the compose setup —
  put HTTPS in front before sharing it (see the README's *Security defaults*).

A report that breaks any of these is exactly what we want to hear about.
