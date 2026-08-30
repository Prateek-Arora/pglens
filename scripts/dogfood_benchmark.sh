#!/usr/bin/env bash
#
# PgLens dogfood benchmark (Phase 2, Step 11) — tune PgLens's OWN metadata schema.
#
# PgLens's metadata store (query_stats) is a real, growing time-series. The trend / top-mover
# queries (TrendRepository.windowTotals / newQueries) scan query_stats by captured_at RANGE across
# ALL queryids — a path the V1 primary key (db_id, queryid, captured_at) does NOT serve (captured_at
# is the 3rd key column, unusable for a range without a queryid equality). The time-series index was
# deliberately WITHHELD from V1 so this benchmark can measure a real before/after (see the DOGFOOD
# NOTE in server/.../V1__init.sql). This is PgLens finding a missing index on itself.
#
# What it does, against a THROWAWAY pgvector container (never the live stack, never a monitored DB):
#   1. apply the real V1..V3 metadata migrations,
#   2. backfill ~WEEKS of query_stats snapshots in captured_at order (as the append-only ingest
#      writes them — so BRIN correlation is faithful, not manufactured),
#   3. EXPLAIN (ANALYZE, BUFFERS) the ACTUAL trend queries: before any trend index, then after
#      BRIN(captured_at), then after btree(db_id, captured_at).
#
# Every number printed is real: planner cost is a labeled ESTIMATE, execution time / buffers are
# MEASURED (median of $RUNS warm runs; parallelism + JIT off so the number reflects the access path,
# not worker scheduling or JIT compile jitter). The row VALUES are synthetic (random deltas) — only
# the table's SIZE and PHYSICAL ORDER drive the result; no claim is made that the deltas model any
# real workload. Transcribe the output into docs/benchmarks.md.
#
# Usage:   bash scripts/dogfood_benchmark.sh          # run, then tear the container down
#          KEEP=1 bash scripts/dogfood_benchmark.sh   # leave the container up to poke at
# Env overrides: QUERIES, WEEKS, STEP_MIN, RUNS, IMAGE, CONTAINER.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIG_DIR="$REPO_ROOT/server/src/main/resources/db/migration"

IMAGE="${IMAGE:-pgvector/pgvector:0.8.6-pg16}"   # same pinned image as the live metadata-db
CONTAINER="${CONTAINER:-pglens-bench}"
PGUSER="pglens"
PGDB="pglens_bench"

QUERIES="${QUERIES:-120}"          # distinct queryids (query_texts rows)
WEEKS="${WEEKS:-26}"               # ~6 months of history so the 2-week window is a small fraction
STEP_MIN="${STEP_MIN:-15}"         # synthetic snapshot cadence (minutes)
RUNS="${RUNS:-5}"                  # timed runs per query; the median is reported
END_TS="2026-08-29 00:00:00+00"    # fixed anchor → reproducible row count

# Deterministic per-run session so the measurement isolates the access path.
SESSION="SET max_parallel_workers_per_gather = 0; SET jit = off; SET work_mem = '256MB';"

# --- helpers ---------------------------------------------------------------------------------------
# Scalar query (tuples-only, unaligned).
psqlq() { docker exec -i "$CONTAINER" psql -tAX -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDB" -c "$1"; }
# Run a heredoc of SQL.
psqlf() { docker exec -i "$CONTAINER" psql -tAX -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDB"; }

# median of the numbers on stdin (numeric sort; middle element).
median() { sort -g | awk '{a[NR]=$1} END{ if(NR==0){print "NaN"} else if(NR%2){print a[(NR+1)/2]} else {printf "%.3f\n",(a[NR/2]+a[NR/2+1])/2} }'; }

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then
    echo "KEEP=1 — leaving container '$CONTAINER' up (host port $(docker port "$CONTAINER" 5432 2>/dev/null || echo '?'))."
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# --- preflight -------------------------------------------------------------------------------------
docker info >/dev/null 2>&1 || { echo "Docker daemon not running — start Docker Desktop." >&2; exit 1; }
[ -f "$MIG_DIR/V1__init.sql" ] || { echo "migrations not found at $MIG_DIR" >&2; exit 1; }

echo "==> Starting throwaway $IMAGE as '$CONTAINER'"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e POSTGRES_USER="$PGUSER" -e POSTGRES_PASSWORD=pglens \
  -e POSTGRES_DB="$PGDB" -P "$IMAGE" >/dev/null

echo -n "    waiting for readiness"
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -q -U "$PGUSER" -d "$PGDB" >/dev/null 2>&1; then break; fi
  echo -n "."; sleep 1
done
echo " ready"

echo "==> Applying metadata migrations V1..V3 (the real Flyway SQL)"
for v in V1__init V2__catalog_tables V3__index_hygiene; do
  docker exec -i "$CONTAINER" psql -tAX -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDB" < "$MIG_DIR/$v.sql" >/dev/null
  echo "    applied $v"
done

echo "==> Backfilling query_stats: $QUERIES queries x $WEEKS weeks @ ${STEP_MIN}min snapshots"
psqlf <<SQL
$SESSION
-- one synthetic monitored db + its query_texts (queryid 1..$QUERIES)
INSERT INTO monitored_dbs (name, host, agent_token_hash) VALUES ('bench', 'localhost', 'x');
INSERT INTO query_texts (db_id, queryid, text_hash, normalized_text, plan_captured)
SELECT 1, g, 'h'||g, 'SELECT * FROM t WHERE k = \$1  -- bench query '||g, true
FROM generate_series(1, $QUERIES) g;

-- The delta time-series, inserted in captured_at order (append-only ingest writes it this way, so
-- the physical/time correlation BRIN relies on is real, not staged). mean = total/calls exactly.
INSERT INTO query_stats (db_id, queryid, captured_at, agent_sample_at,
                         calls_delta, total_exec_time_delta_ms, mean_exec_time_ms,
                         rows_delta, shared_blks_hit_delta, shared_blks_read_delta)
SELECT 1, q.queryid, ts, ts,
       v.calls,
       v.calls * v.percall,
       v.percall,
       v.calls * v.rowsper,
       v.calls * 8,
       v.calls
FROM generate_series(
       TIMESTAMPTZ '$END_TS' - INTERVAL '$WEEKS weeks',
       TIMESTAMPTZ '$END_TS' - INTERVAL '$STEP_MIN minutes',
       INTERVAL '$STEP_MIN minutes') AS ts
CROSS JOIN generate_series(1, $QUERIES) AS q(queryid)
CROSS JOIN LATERAL (
  SELECT (5 + floor(random()*50))::bigint            AS calls,
         (0.5 + random()*20)::double precision       AS percall,
         (1 + floor(random()*100))::bigint           AS rowsper
) AS v
ORDER BY ts;

ANALYZE query_stats;
ANALYZE query_texts;
SQL
ROWS="$(psqlq "SELECT count(*) FROM query_stats;")"
SIZE="$(psqlq "SELECT pg_size_pretty(pg_total_relation_size('query_stats'));")"
echo "    query_stats: $ROWS rows, $SIZE"

# The ACTUAL trend queries (verbatim from TrendRepository), with concrete window bounds:
#   recent_to = END; recent_from = END-7d; prior_from = END-14d  (week-over-week top movers).
WINDOW_TOTALS="$(cat <<SQL
SELECT s.queryid, t.normalized_text,
       COALESCE(sum(s.total_exec_time_delta_ms) FILTER (WHERE s.captured_at >= TIMESTAMPTZ '$END_TS' - INTERVAL '7 days'), 0)  AS recent_total,
       COALESCE(sum(s.calls_delta)              FILTER (WHERE s.captured_at >= TIMESTAMPTZ '$END_TS' - INTERVAL '7 days'), 0)  AS recent_calls,
       COALESCE(sum(s.total_exec_time_delta_ms) FILTER (WHERE s.captured_at <  TIMESTAMPTZ '$END_TS' - INTERVAL '7 days'), 0)  AS prior_total,
       COALESCE(sum(s.calls_delta)              FILTER (WHERE s.captured_at <  TIMESTAMPTZ '$END_TS' - INTERVAL '7 days'), 0)  AS prior_calls
FROM query_stats s
JOIN query_texts t ON t.db_id = s.db_id AND t.queryid = s.queryid
WHERE s.db_id = 1 AND s.captured_at >= TIMESTAMPTZ '$END_TS' - INTERVAL '14 days' AND s.captured_at < TIMESTAMPTZ '$END_TS'
GROUP BY s.queryid, t.normalized_text
SQL
)"
NEW_QUERIES="$(cat <<SQL
SELECT s.queryid, t.normalized_text, min(s.captured_at) AS first_seen,
       sum(s.total_exec_time_delta_ms) AS recent_total, sum(s.calls_delta) AS recent_calls
FROM query_stats s
JOIN query_texts t ON t.db_id = s.db_id AND t.queryid = s.queryid
WHERE s.db_id = 1 AND s.captured_at >= TIMESTAMPTZ '$END_TS' - INTERVAL '7 days' AND s.captured_at < TIMESTAMPTZ '$END_TS'
  AND NOT EXISTS (SELECT 1 FROM query_stats p WHERE p.db_id = s.db_id AND p.queryid = s.queryid AND p.captured_at < TIMESTAMPTZ '$END_TS' - INTERVAL '7 days')
GROUP BY s.queryid, t.normalized_text
HAVING sum(s.total_exec_time_delta_ms) >= 0
ORDER BY recent_total DESC
SQL
)"

# cost_estimate: planner's total-cost estimate for the top node (EXPLAIN without ANALYZE).
cost_of() {
  docker exec -i "$CONTAINER" psql -tAX -U "$PGUSER" -d "$PGDB" <<SQL 2>/dev/null | grep -oE 'cost=[0-9.]+\.\.[0-9.]+' | head -n1 | sed -E 's/.*\.\.([0-9.]+)/\1/'
$SESSION
EXPLAIN $1
SQL
}
# scan node touching query_stats (proves the access-path change), from one ANALYZE run.
scannode_of() {
  docker exec -i "$CONTAINER" psql -tAX -U "$PGUSER" -d "$PGDB" <<SQL 2>/dev/null | grep -iE 'Scan.*query_stats' | head -n1 | sed -E 's/^[ ->]*//; s/  \(cost.*//'
$SESSION
EXPLAIN $1
SQL
}
# one EXPLAIN (ANALYZE, BUFFERS) run → full text.
analyze_run() {
  docker exec -i "$CONTAINER" psql -tAX -U "$PGUSER" -d "$PGDB" <<SQL 2>/dev/null
$SESSION
EXPLAIN (ANALYZE, BUFFERS) $1
SQL
}

# Measure one query in the current index state: node, cost estimate, buffers, median exec ms.
measure() {
  local label="$1" query="$2"
  local node cost exec_ms buffers
  node="$(scannode_of "$query")"
  cost="$(cost_of "$query")"
  local one; one="$(analyze_run "$query")"                    # a representative run for buffers
  buffers="$(printf '%s' "$one" | grep -oE 'shared hit=[0-9]+( read=[0-9]+)?' | head -n1)"
  local times=()
  for _ in $(seq 1 "$RUNS"); do
    exec_ms="$(analyze_run "$query" | grep -oE 'Execution Time: [0-9.]+' | grep -oE '[0-9.]+')"
    times+=("$exec_ms")
  done
  local med; med="$(printf '%s\n' "${times[@]}" | median)"
  printf '    %-24s node=%-38s cost_est=%-12s exec_median=%8s ms  buffers=%s\n' \
    "$label" "${node:-?}" "${cost:-?}" "$med" "${buffers:-?}"
}

echo "==> Measuring (parallelism + JIT OFF; median of $RUNS warm runs)"
echo
echo "  windowTotals  (week-over-week top movers — 14-day cross-query scan):"
psqlq "DROP INDEX IF EXISTS query_stats_captured_brin; DROP INDEX IF EXISTS query_stats_db_captured;" >/dev/null
measure "BEFORE (no trend index)" "$WINDOW_TOTALS"
psqlq "CREATE INDEX query_stats_captured_brin ON query_stats USING brin (captured_at); ANALYZE query_stats;" >/dev/null
measure "AFTER BRIN(captured_at)" "$WINDOW_TOTALS"
BRIN_SIZE="$(psqlq "SELECT pg_size_pretty(pg_relation_size('query_stats_captured_brin'));")"
psqlq "DROP INDEX query_stats_captured_brin; CREATE INDEX query_stats_db_captured ON query_stats (db_id, captured_at); ANALYZE query_stats;" >/dev/null
measure "AFTER btree(db_id,cap_at)" "$WINDOW_TOTALS"
BTREE_SIZE="$(psqlq "SELECT pg_size_pretty(pg_relation_size('query_stats_db_captured'));")"
psqlq "DROP INDEX query_stats_db_captured;" >/dev/null

echo
echo "  newQueries    (new-slow-query detection — 7-day scan + first-seen anti-join):"
measure "BEFORE (no trend index)" "$NEW_QUERIES"
psqlq "CREATE INDEX query_stats_captured_brin ON query_stats USING brin (captured_at); ANALYZE query_stats;" >/dev/null
measure "AFTER BRIN(captured_at)" "$NEW_QUERIES"
psqlq "DROP INDEX query_stats_captured_brin; CREATE INDEX query_stats_db_captured ON query_stats (db_id, captured_at); ANALYZE query_stats;" >/dev/null
measure "AFTER btree(db_id,cap_at)" "$NEW_QUERIES"
psqlq "DROP INDEX query_stats_db_captured;" >/dev/null

echo
echo "==> Index sizes: BRIN=$BRIN_SIZE   btree(db_id,captured_at)=$BTREE_SIZE   (table=$SIZE)"
echo "==> Done. Transcribe the numbers above into docs/benchmarks.md (cost=estimate, exec/buffers=measured)."
