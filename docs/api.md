# The HTTP API

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
| `GET /overview?window=24h\|7d\|30d` | the dashboard's first screen: measured time, how much of it is in queries with an index to try, the planner-estimated saving, the top indexes, applied indexes, one point per UTC hour |
| `GET /databases/{db}/timeline?window&series` | a database's measured time per UTC hour, its top queries' shares, and the estimated saving |
| `GET /databases/{db}/applied` | recommended indexes that now exist, with each query's **measured** mean time before and after |
| `GET /system` | the server's version and how explanations are written (template, or which model) |

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

API tokens can read everything and change nothing; list or revoke them at `/api/v1/api-tokens`. Admins manage users at `/api/v1/users` (roles `ADMIN` / `VIEWER`) and databases at `/api/v1/databases` (`POST {"name": …}` returns the agent token **once**; `POST /databases/{db}/token` rotates it; `DELETE /databases/{db}?confirm={db}` deletes PgLens's history for it — never the database itself). Speed on a 30-day, 500-query history: every endpoint under 60 ms p95 (2026-09-27) (`make bench-api`, [`docs/benchmarks.md`](benchmarks.md)).
