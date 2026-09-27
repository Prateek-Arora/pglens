# Running PgLens for real

The [quickstart](../README.md#quickstart) runs PgLens next to a bundled demo database. This page is
for watching **your own** databases: preparing them, where the agents run, TLS and HTTPS, your own
LLM, and keeping PgLens healthy over months (disk, backups, upgrades).

- [1. Start PgLens without the demo](#1-start-pglens-without-the-demo)
- [2. Prepare each database](#2-prepare-each-database)
- [3. Add the database and start its agent](#3-add-the-database-and-start-its-agent)
- [4. Managed Postgres and connection poolers](#4-managed-postgres-and-connection-poolers)
- [5. Security: TLS, logins and HTTPS](#5-security-tls-logins-and-https)
- [6. Plain-language explanations with your own model](#6-plain-language-explanations-with-your-own-model)
- [7. Disk, retention, backups and upgrades](#7-disk-retention-backups-and-upgrades)
- [8. Troubleshooting](#8-troubleshooting)

## 1. Start PgLens without the demo

```bash
git clone https://github.com/Prateek-Arora/pglens.git && cd pglens
cp deploy/compose/.env.example deploy/compose/.env   # then set POSTGRES_PASSWORD (see §5)
make up-no-demo          # PgLens's own store, the server and the dashboard — built from source
# or, with the released images and no build:
docker compose -f deploy/compose/docker-compose.yml up -d
```

`make up-no-demo` writes a generated admin password into `deploy/compose/.env`
(`grep PGLENS_ADMIN_PASSWORD deploy/compose/.env`). With plain `docker compose up` and no
`PGLENS_ADMIN_PASSWORD` set, the server logs a generated one once: `docker compose -f
deploy/compose/docker-compose.yml logs server | grep -i password`. Open <http://localhost:3000> and sign
in as `admin`.

The demo database and its agent are the compose profile `demo`; they only start with `make up` or
`--profile demo`.

## 2. Prepare each database

Once per database, as a superuser or the tables' owner:

- **PostgreSQL 16 or newer** (older servers are refused with a clear message; CI runs 16, 17 and 18).
- **`pg_stat_statements`** in `shared_preload_libraries` (a restart), then created in the database.
- **`hypopg`** — optional but recommended: without it PgLens still finds problems and suggests
  indexes, labeled *not planner-validated*.
- **A read-only login role** that can read the statistics *and* the tables (Postgres won't plan a
  query on a table the role can't read):

```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS hypopg;
CREATE ROLE pglens_ro LOGIN PASSWORD '<a strong password>';
GRANT pg_read_all_stats TO pglens_ro;
-- for every schema your application's tables live in (public, app, billing, …):
GRANT USAGE ON SCHEMA public TO pglens_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO pglens_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO pglens_ro;  -- as the tables' owner
```

Missed a schema? The agent's log (and `pglens scan`) name each schema the role can't read with the
exact `GRANT`s, the dashboard shows the reason on each query without a plan, and the agent picks the
plans up by itself once access is granted.

PgLens never writes to this database. Its connection is read-only from the moment it opens
(`default_transaction_read_only`, plus statement and lock timeouts, as connection startup options),
on top of the role having no write grant — so a write is refused twice, by the database itself.

## 3. Add the database and start its agent

In the dashboard, **Overview → Add a database** shows the agent's token **once**, with an
`agent.env` to fill in and a `docker run` command. One agent per database; it runs next to the
database and only dials **out** to the server's gRPC port 9090 (TLS).

| Setting (agent) | What |
|---|---|
| `PGLENS_MONITORED_DB_URL` | `postgresql://pglens_ro:<password>@<host>:5432/<db>` — add `?sslmode=require` (or `verify-full`) for a server that needs TLS |
| `PGLENS_DB_NAME`, `PGLENS_AGENT_TOKEN` | from *Add a database* (or `POST /api/v1/databases`) |
| `PGLENS_SERVER_HOST`, `PGLENS_SERVER_PORT` | where the PgLens server's gRPC port is |
| `PGLENS_SERVER_CA_CERT` | the CA that signed the server's certificate (see §5) |
| `PGLENS_SAMPLE_INTERVAL_MS` | default `60000` (one sample a minute) |
| `PGLENS_PLAN_REFRESH_MS` | default `3600000`: each plan is captured again at least hourly, and at once when the database's indexes change |

**Same machine as PgLens?** Use `host.docker.internal` for the database or server host (the
`docker run` command adds `--add-host=host.docker.internal:host-gateway` for Linux). **Another
machine?** The server's certificate must name the host the agent dials — see §5.

Data appears from the agent's **second** sample: the first one only anchors each query's counters.

**What the agent costs the database.** Each sample reads `pg_stat_statements` and the catalog; plans
are `EXPLAIN (GENERIC_PLAN)` (never `ANALYZE`) and HypoPG checks run only for the candidate
indexes the server asks about; every statement runs under the 30 s `statement_timeout`. Measured on
the demo database (2026-09-27: 13 tracked queries, default settings, 2.9 hours with the agent
running throughout): the agent's role used **3.8 s of database time, about 1.3 s an hour** (around
50 statements a minute), and the agent container ~260 MiB of memory. The catalog
reads grow with the number of tables and indexes, and the checks with the number of distinct slow
queries — `pg_stat_statements` on your database shows exactly what the agent's role spends.

## 4. Managed Postgres and connection poolers

PgLens needs `pg_stat_statements` (and ideally `hypopg`) — most managed services offer both; enable
them the way your provider documents (a parameter group or a flags page for
`shared_preload_libraries`, then `CREATE EXTENSION`). `[ASSUMPTION: availability varies by
provider and version — PgLens's CI tests stock PostgreSQL 16–18, not managed services.]`

**Connect directly, not through a transaction-mode pooler.** HypoPG's hypothetical indexes and
PgLens's read-only guard both live in one database session. A pooler in *transaction* mode hands
each statement to whichever server connection is free, so they would silently not apply — and a
session setting could even leak onto your application's connections. PgLens therefore sets its
guards as connection startup options, which a pooler refuses, and stops with a message saying to
connect directly. Use:

- **Supabase:** the *direct connection* string (port 5432), not the transaction pooler (port 6543).
- **Neon:** the endpoint host *without* `-pooler`.
- **PgBouncer / RDS Proxy / pgcat:** the database's own address, or a *session*-mode pool.

Add `?sslmode=require` to the URL when the provider requires TLS (they usually do).

## 5. Security: TLS, logins and HTTPS

- **Agent ↔ server gRPC is TLS.** The one-shot `certs` service creates a dev CA and a server
  certificate for `server`, `localhost`, `127.0.0.1`, `host.docker.internal` and anything in
  `PGLENS_TLS_EXTRA_SANS` (e.g. `DNS:pglens.internal,IP:10.0.0.5`), and throws the CA's key away.
  Changing the names re-issues the certificates (and the CA): copy the new `ca.pem` to every agent.
  Get it with `docker compose -f deploy/compose/docker-compose.yml run --rm --no-deps --entrypoint
  cat certs /certs/ca/ca.pem > ca.pem`. For a real deployment give the server your own certificate
  (`PGLENS_GRPC_TLS_CERT`, `PGLENS_GRPC_TLS_KEY`) and the agents its CA (`PGLENS_SERVER_CA_CERT`).
  Plaintext needs `PGLENS_GRPC_PLAINTEXT=true` on the server *and* `PGLENS_SERVER_PLAINTEXT=true` on
  the agent, and is logged as a warning.
- **Logins.** The first admin's password comes from `PGLENS_ADMIN_PASSWORD` (12+ characters). Passwords
  are bcrypt hashes and tokens SHA-256 hashes; 5 failed logins lock a username out for 15 minutes.
  Admins add users (`ADMIN` / `VIEWER`) and read-only API tokens in *Settings*.
  `PGLENS_AUTH_MODE=none` turns logins off — only for one person on their own machine.
- **Only the gRPC port (9090) is open to the network.** The dashboard (3000), the API (8080) and both
  databases listen on `127.0.0.1`. Set your own `POSTGRES_PASSWORD` in `deploy/compose/.env`
  **before the first start** (it is applied when the data volumes are created); PgLens's store holds
  every monitored database's query text.
- **To share the dashboard, put HTTPS in front**, e.g. [Caddy](https://caddyserver.com), which gets a
  certificate automatically (the dashboard then uses a `Secure`, `__Host-` cookie):

  ```
  pglens.example.com {
      reverse_proxy 127.0.0.1:3000
  }
  ```

  Add `header Strict-Transport-Security "max-age=31536000"` there once HTTPS works.

## 6. Plain-language explanations with your own model

Each query page explains its suggested index in plain language. With no model configured, that text
is PgLens's own template and every number in it is exact — nothing else is needed. To let a model
rewrite it, set in `deploy/compose/.env` and restart the server (`make up` or `docker compose up -d`):

| Setting (server) | Example |
|---|---|
| `PGLENS_EXPLAIN_ENABLED` | `true` |
| `PGLENS_LLM_URL` | any OpenAI-compatible `/v1` endpoint: `http://llm:11434/v1` (`make llm-up`), `http://host.docker.internal:11434/v1` (native Ollama), llama.cpp, vLLM, LM Studio |
| `PGLENS_LLM_MODEL` | `qwen3.5:4b` (the evaluated default) |
| `PGLENS_LLM_API_KEY`, `PGLENS_LLM_ALLOW_REMOTE` | only for a hosted API: its key, and `true` — endpoints outside this machine or private network are refused otherwise |
| `PGLENS_LLM_EMBEDDING_MODEL` | `nomic-embed-text` for the "read more" docs links (768-dimension vectors; another size just turns the links off) |

*Settings → Plain-language explanations* shows the model, its host, whether it is remote, and the
last pass (how many answers passed PgLens's checks and how many fell back to the template, and why).
Every answer is checked against PgLens's facts; a failing one is retried once, then replaced by the
template. The prompt holds normalized query text (`$1`), table and column names and plan facts —
never sampled values or connection details. With a hosted API, check its data terms first. Details
and the evaluation: [`docs/cli.md`](cli.md#plain-language-explanations----plain),
[`docs/llm-eval.md`](llm-eval.md).

## 7. Disk, retention, backups and upgrades

**What PgLens stores** (in its own Postgres, `metadata-db`): each monitored query's normalized text
and generic plan, a per-sample time-series of its counters, an hourly rollup of it, per-sample
index and table counters (for "unused index" and write-load evidence), and recommendations.

**Retention.** Per-sample rows older than `PGLENS_RETENTION_RAW_DAYS` (default **35**, at least 31)
are deleted hourly. The hourly rollup, which every dashboard window, trend and before/after
comparison reads, is kept. The raw series grows with *queries × samples*: measured at about 18 MB
per query-year at 5-minute sampling ([`docs/benchmarks.md`](benchmarks.md)), so the default 60-second
sampling is roughly 5× that, capped at 35 days. Lower `PGLENS_SAMPLE_TOP_N` (default 200 queries per
database) or raise `PGLENS_SAMPLE_INTERVAL_MS` on the agent to store less.

**Backups.** Everything PgLens knows is in `metadata-db`:

```bash
docker compose -f deploy/compose/docker-compose.yml exec -T metadata-db \
  pg_dump -U pglens -Fc pglens_meta > pglens-$(date +%F).dump
# restore into a fresh stack:
docker compose -f deploy/compose/docker-compose.yml exec -T metadata-db \
  pg_restore -U pglens -d pglens_meta --clean --if-exists < pglens-2026-09-27.dump
```

**Upgrades.** Back up, then set `PGLENS_VERSION` in `deploy/compose/.env` to the new release and run
`docker compose -f deploy/compose/docker-compose.yml pull && docker compose -f
deploy/compose/docker-compose.yml up -d` (or `git pull && make up-no-demo` from source). The server
migrates its store on start; migrations are forward-only, so going back to an older release needs
the backup. Upgrade the agents to the same release (the dashboard's *Settings → About* shows the
server's version next to its own). Release notes: [`CHANGELOG.md`](../CHANGELOG.md).

**Removing a database** (*Settings → Databases → Delete*) deletes PgLens's history for it — never the
database itself. Stop its agent too.

## 8. Troubleshooting

| Symptom | Where to look |
|---|---|
| The agent logs `UNAUTHENTICATED` | its `PGLENS_AGENT_TOKEN` is wrong or was rotated — rotate it in *Settings* and restart the agent |
| The agent can't connect over TLS | its log names the problem; usually the server's certificate doesn't name the host the agent dials (`PGLENS_TLS_EXTRA_SANS`), or the agent has an old `ca.pem` |
| `read-only guard isn't in effect … connection pooler` | connect directly (§4) |
| Queries but no plans ("can't be generically explained" / permission denied) | the role can't read those tables — the agent's log prints the `GRANT`s (§2) |
| "No query activity in this window yet" | the first sample only anchors counters: wait one interval after traffic |
| A recommendation disappeared | the engine no longer proposes it: the plan changed, or the index was built — then it moves to *Built and measured* with its measured before/after |
| Explanations say "template" | no model configured, or the model's answers failed PgLens's checks — see *Settings* |
