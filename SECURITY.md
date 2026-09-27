# Security policy

## Supported versions

Only the latest release gets security fixes while PgLens is pre-1.0.

## Reporting a vulnerability

Please report it privately through GitHub: **Security → Report a vulnerability** on this
repository. Don't open a public issue. Expect an answer within a week; a fix and a release follow
as soon as one is ready, and the report is credited unless you'd rather not.

## What PgLens promises

- **It never writes to the database it monitors.** The agent and the CLI connect as a read-only
  role, and the connection is read-only from startup: `default_transaction_read_only` is passed as a
  connection option, not set with a session `SET` that a transaction-mode pooler could leak onto
  other clients. Such a pooler is refused. HypoPG indexes are hypothetical and last only for the
  session. The one exception is `pglens confirm`, which runs `CREATE INDEX` and `DROP INDEX` only on a
  copy its owner marked with `ALTER DATABASE … SET pglens.scratch = 'on'`.
- **Query values stay in your database session.** `pg_stat_statements` text is normalized (`$1`),
  and the sampled values used for estimates never leave the session. An LLM endpoint outside this
  machine or private network is refused unless you explicitly allow it.
- **Logins are on by default.** Passwords are stored as bcrypt hashes and tokens as SHA-256 hashes.
  Agents talk to the server over TLS. In the compose setup, the dashboard, the HTTP API and both
  databases listen on `127.0.0.1`; put HTTPS in front before you share it
  ([docs/operations.md](docs/operations.md#5-security-tls-logins-and-https)).

If you find a way to break any of these, please report it.
