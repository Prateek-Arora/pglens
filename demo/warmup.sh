#!/usr/bin/env bash
#
# Replay the slow-query pack repeatedly so pg_stat_statements accumulates real
# stats (calls, total_exec_time, rows). Runs the queries INSIDE the container,
# so no host `psql` is required.
#
# Usage:  bash demo/warmup.sh          # default 25 iterations
#         ITERATIONS=50 bash demo/warmup.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/deploy/compose/docker-compose.yml"
ITERATIONS="${ITERATIONS:-25}"
DB_USER="${POSTGRES_USER:-pglens}"
DB_NAME="${MONITORED_DB:-pglens_demo}"

echo "Warming up: ${ITERATIONS} iteration(s) of demo/slow_queries.sql ..."
for ((i = 1; i <= ITERATIONS; i++)); do
    docker compose -f "$COMPOSE_FILE" exec -T monitored-db \
        psql -v ON_ERROR_STOP=1 -q -U "$DB_USER" -d "$DB_NAME" \
        <"$REPO_ROOT/demo/slow_queries.sql" >/dev/null
    printf '.'
done
printf '\n'
echo "Done. pg_stat_statements now holds stats for the demo queries."
