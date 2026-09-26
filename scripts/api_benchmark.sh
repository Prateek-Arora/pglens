#!/usr/bin/env bash
#
# PgLens API latency benchmark (Phase 4 Step 5, ADR-0044) — how fast is PgLens's own read API on a
# big history? A slow performance tool is the embarrassing failure mode.
#
# Against THROWAWAY containers only (never the live stack, never a monitored DB):
#   1. a pgvector metadata Postgres + the real server image; the server's Flyway applies every
#      migration, exactly as in production;
#   2. a synthetic history: QUERIES queries x DAYS days x STEP_MIN-minute intervals, inserted in
#      captured_at order (as ingest appends it). Dense — every query has a row in every interval —
#      the worst case: real idle intervals write no row (ADR-0034);
#   3. each read endpoint timed over HTTP (curl, loopback) RUNS times after WARMUP untimed calls:
#      p50 / p95 / max, plus the response size; and EXPLAIN (ANALYZE, BUFFERS, TIMING OFF) of the
#      30-day leaderboard SQL under the server's own settings.
#
# Every time printed is measured; the row VALUES are synthetic (random deltas) — only the history's
# size and physical order drive the result. Transcribe the output into docs/benchmarks.md.
#
# Usage:   bash scripts/api_benchmark.sh          # build, run, tear down
#          KEEP=1 bash scripts/api_benchmark.sh   # leave the containers up to poke at
# Env overrides: QUERIES, DAYS, STEP_MIN, RUNS, WARMUP, SKIP_BUILD=1.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUERIES="${QUERIES:-500}"
DAYS="${DAYS:-30}"
STEP_MIN="${STEP_MIN:-5}"
RUNS="${RUNS:-40}"
WARMUP="${WARMUP:-3}"
NET="pglens-apibench"
DBC="pglens-apibench-db"
SRV="pglens-apibench-server"
IMAGE="pglens/server:apibench"
PGUSER="pglens"
PGDB="pglens_meta"
S="${SCRATCH:-$(mktemp -d)}"

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then
    echo "KEEP=1 — leaving $DBC and $SRV up."
  else
    docker rm -f "$SRV" "$DBC" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
  fi
  rm -f "$S/auth.cfg"
}
trap cleanup EXIT

psqlf() { docker exec -i "$DBC" psql -tAX -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDB"; }
psqlq() { docker exec -i "$DBC" psql -tAX -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDB" -c "$1"; }

docker info >/dev/null 2>&1 || { echo "Docker daemon not running — start Docker Desktop." >&2; exit 1; }

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> Building the server jar + image"
  (cd "$REPO_ROOT" && ./gradlew -q :server:bootJar)
  docker build -q -t "$IMAGE" -f "$REPO_ROOT/deploy/compose/server/Dockerfile" "$REPO_ROOT" >/dev/null
fi

echo "==> Starting a throwaway metadata Postgres + the server"
docker rm -f "$SRV" "$DBC" >/dev/null 2>&1 || true
docker network rm "$NET" >/dev/null 2>&1 || true
docker network create "$NET" >/dev/null
docker run -d --name "$DBC" --network "$NET" --shm-size=1g -e POSTGRES_USER="$PGUSER" \
  -e POSTGRES_PASSWORD=pglens -e POSTGRES_DB="$PGDB" pgvector/pgvector:0.8.6-pg16 >/dev/null
until docker exec "$DBC" pg_isready -q -U "$PGUSER" -d "$PGDB" >/dev/null 2>&1; do sleep 1; done

ADMIN_PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 || true)"
# Plaintext gRPC is fine here: nothing connects to it, and it is not published.
docker run -d --name "$SRV" --network "$NET" -p 127.0.0.1::8080 \
  -e PGLENS_METADATA_URL="jdbc:postgresql://$DBC:5432/$PGDB" \
  -e PGLENS_METADATA_USER="$PGUSER" -e PGLENS_METADATA_PASSWORD=pglens \
  -e PGLENS_GRPC_PLAINTEXT=true -e PGLENS_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  -e PGLENS_ANALYSIS_INITIAL_DELAY_MS=86400000 "$IMAGE" >/dev/null
PORT="$(docker port "$SRV" 8080 | head -1 | sed 's/.*://')"
API="http://127.0.0.1:$PORT/api/v1"
for _ in $(seq 1 90); do
  curl -sf "$API/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$API/health" >/dev/null || { docker logs "$SRV" | tail -20; exit 1; }
echo "    server up (migrations applied by Flyway): $(psqlq "SELECT max(version) FROM flyway_schema_history")"

echo "==> Backfilling: $QUERIES queries x $DAYS days @ ${STEP_MIN} min (dense, time-ordered)"
psqlf <<SQL
INSERT INTO monitored_dbs (name, host, agent_token_hash) VALUES ('bench', 'bench', 'x');
INSERT INTO query_texts (db_id, queryid, text_hash, normalized_text, plan_captured)
SELECT 1, (g * 7919 - 1000000)::bigint * 1000003, 'h' || g,
       'SELECT o.id, o.total_cents FROM orders o JOIN customers c ON c.id = o.customer_id '
         || 'WHERE o.status = \$1 AND c.region = \$2 ORDER BY o.created_at DESC LIMIT \$3 -- q' || g,
       false
FROM generate_series(1, $QUERIES) g;
INSERT INTO query_stats (db_id, queryid, captured_at, agent_sample_at, calls_delta,
                         total_exec_time_delta_ms, mean_exec_time_ms, rows_delta,
                         shared_blks_hit_delta, shared_blks_read_delta)
SELECT 1, t.queryid, ts, ts, v.calls, v.calls * v.percall, v.percall, v.calls * 10, v.calls * 8,
       v.calls
FROM generate_series(now() - INTERVAL '$DAYS days', now() - INTERVAL '1 minute',
                     INTERVAL '$STEP_MIN minutes') AS ts
CROSS JOIN query_texts t
CROSS JOIN LATERAL (SELECT (1 + floor(random() * 50))::bigint AS calls,
                           (0.5 + random() * 20)::double precision AS percall
                    WHERE t.queryid IS NOT NULL) v
ORDER BY ts;
INSERT INTO query_cumulative (db_id, queryid, calls, total_exec_time_ms, rows, shared_blks_hit,
                              shared_blks_read, captured_at)
SELECT 1, queryid, sum(calls_delta), sum(total_exec_time_delta_ms), sum(rows_delta), 0, 0, now()
FROM query_stats GROUP BY queryid;
-- A validated recommendation for every tenth query, so the leaderboard's verdict lookup has work.
INSERT INTO recommendations (db_id, queryid, ddl, access_method, status, before_cost, after_cost,
                             relative_drop, used, reason, estimated_ms_saved, score_basis)
SELECT 1, queryid, 'CREATE INDEX idx_' || abs(queryid) || ' ON orders (status);', 'BTREE',
       'PLANNER_VALIDATED', 1000, 100, 0.9, true, 'bench', 100, 'GENERIC_PLAN'
FROM query_texts WHERE abs(queryid) % 10 = 3;
VACUUM ANALYZE;
SQL
echo "    query_stats: $(psqlq "SELECT count(*) FROM query_stats") rows," \
  "$(psqlq "SELECT pg_size_pretty(pg_total_relation_size('query_stats'))")"

umask 077
printf '{"username":"admin","password":"%s"}' "$ADMIN_PASSWORD" \
  | curl -sf -X POST "$API/auth/login" -H 'Content-Type: application/json' --data @- \
  | sed -n 's/.*"token":"\([^"]*\)".*/header = "Authorization: Bearer \1"/p' >"$S/auth.cfg"
QID="$(psqlq "SELECT queryid FROM query_texts ORDER BY queryid LIMIT 1")"

# time_total per call → p50 / p95 / max in ms, and the response size.
measure() { # label path
  local out="$S/times" bytes
  : >"$out"
  for _ in $(seq 1 "$WARMUP"); do curl -sf --config "$S/auth.cfg" -o /dev/null "$API$2"; done
  for _ in $(seq 1 "$RUNS"); do
    curl -sf --config "$S/auth.cfg" -o /dev/null -w '%{time_total}\n' "$API$2" >>"$out"
  done
  bytes="$(curl -sf --config "$S/auth.cfg" "$API$2" | wc -c | tr -d ' ')"
  sort -g "$out" | awk -v label="$1" -v bytes="$bytes" '{a[NR]=$1*1000}
    END { p50=a[int((NR+1)/2)]; p95=a[int(NR*0.95+0.999)]; printf "| %-34s | %7.1f | %7.1f | %7.1f | %9s |\n", label, p50, p95, a[NR], bytes }'
}

echo
echo "==> Endpoint latency over HTTP ($RUNS runs after $WARMUP warm-up; ms)"
echo "| endpoint                           |     p50 |     p95 |     max |     bytes |"
echo "|------------------------------------|---------|---------|---------|-----------|"
measure "leaderboard 24h (total, 50)" "/databases/bench/queries?window=24h"
measure "leaderboard 7d (total, 50)" "/databases/bench/queries?window=7d"
measure "leaderboard 30d (total, 50)" "/databases/bench/queries?window=30d"
measure "leaderboard 30d (mean, 200)" "/databases/bench/queries?window=30d&sort=mean&limit=200"
measure "query detail (30d window)" "/databases/bench/queries/$QID?window=30d"
measure "trend, last 24h" "/databases/bench/queries/$QID/trend"
measure "trend, 30 days" "/databases/bench/queries/$QID/trend?from=$(date -u -v-30d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '30 days ago' +%Y-%m-%dT%H:%M:%SZ)"
measure "top movers 7d" "/databases/bench/top-movers?window=7d"
measure "new slow 24h" "/databases/bench/new-slow?window=24h"
measure "recommendations" "/databases/bench/recommendations"

echo
echo "==> EXPLAIN (ANALYZE, BUFFERS, TIMING OFF): the 30-day leaderboard's aggregation (as the API runs it)"
psqlf <<SQL
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)
SELECT queryid, sum(calls) AS calls, sum(total_exec_time_ms) AS total_ms
FROM query_stats_hourly
WHERE db_id = 1 AND hour >= date_trunc('hour', now() - INTERVAL '30 days', 'UTC') AND hour < now()
GROUP BY queryid ORDER BY total_ms DESC LIMIT 50;
SQL

echo
echo "==> Ingest cost of the rollup trigger: one interval ($QUERIES rows), median of 5 (rolled back)"
for _ in 1 2 3 4 5; do
  psqlf <<SQL | sed -n 's/^Trigger query_stats_rollup: time=\([0-9.]*\).*/\1/p; s/^Execution Time: \([0-9.]*\) ms/total \1/p' | paste -sd' ' -
BEGIN;
EXPLAIN (ANALYZE)
INSERT INTO query_stats (db_id, queryid, captured_at, agent_sample_at, calls_delta,
                         total_exec_time_delta_ms, mean_exec_time_ms, rows_delta,
                         shared_blks_hit_delta, shared_blks_read_delta)
SELECT 1, queryid, now(), now(), 5, 10.0, 2.0, 50, 40, 5 FROM query_texts;
ROLLBACK;
SQL
done | sort -k3 -g | sed -n 3p | awk '{printf "    trigger %.1f ms of a %.1f ms insert (median run)\n", $1, $3}'
