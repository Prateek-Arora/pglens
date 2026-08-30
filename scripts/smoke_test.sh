#!/usr/bin/env bash
#
# PgLens Phase 0 smoke test -- the reproducibility gate and the Phase 1 oracle.
#
# End to end: bring up the stack, prove both extensions exist (and that hypopg
# actually functions), seed, warm up, then assert pg_stat_statements captured
# the demo queries and that the flagship slow query really produces a Seq Scan.
# Asserts on PLAN SHAPE and row counts -- never wall-clock latency -- so it is
# not flaky in CI.
#
# Exits non-zero if any assertion fails. Run: `make test`.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/deploy/compose/docker-compose.yml"
DB_USER="${POSTGRES_USER:-pglens}"
MON_DB="${MONITORED_DB:-pglens_demo}"
META_DB="${METADATA_DB:-pglens_meta}"

pass=0
fail=0
ok()  { echo "  [PASS] $1"; pass=$((pass + 1)); }
bad() { echo "  [FAIL] $1"; fail=$((fail + 1)); }

# assert "<label>" <command...> -- PASS if the command succeeds, else FAIL.
assert() {
    local label="$1"
    shift
    if "$@"; then ok "$label"; else bad "$label"; fi
}

# Run SQL and return a single trimmed scalar (-t tuples-only, -A unaligned).
mon()  { docker compose -f "$COMPOSE_FILE" exec -T monitored-db psql -tAX -U "$DB_USER" -d "$MON_DB" -c "$1"; }
meta() { docker compose -f "$COMPOSE_FILE" exec -T metadata-db  psql -tAX -U "$DB_USER" -d "$META_DB" -c "$1"; }

echo "==> Preflight: Docker daemon"
if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not running. Start Docker Desktop and retry." >&2
    exit 1
fi

# Only the data-plane dbs — the smoke test is the Phase-0/1 reproducibility gate, not the app loop
# (the agent→server→metadata flow is covered deterministically by AgentToServerFlowIntegrationTest).
# Naming the services also avoids building the server/agent images, whose COPY needs host-built jars
# this script never produces.
echo "==> Bringing up the data-plane dbs (build + wait for health)"
if ! docker compose -f "$COMPOSE_FILE" up -d --build --wait monitored-db metadata-db; then
    echo "compose up failed" >&2
    exit 1
fi

echo "==> [1/5] Extensions installed"
assert "pg_stat_statements present (monitored-db)" \
    test "$(mon "SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements';")" = "1"
assert "hypopg present (monitored-db)" \
    test "$(mon "SELECT 1 FROM pg_extension WHERE extname = 'hypopg';")" = "1"
assert "vector present (metadata-db)" \
    test "$(meta "SELECT 1 FROM pg_extension WHERE extname = 'vector';")" = "1"

echo "==> [2/5] hypopg actually works (hypothetical-index round-trip)"
mon "SELECT hypopg_reset();" >/dev/null
hypo_n="$(mon "SELECT count(*) FROM hypopg_create_index('CREATE INDEX ON orders(customer_id)');")"
assert "hypopg_create_index created a hypothetical index (got '${hypo_n}')" \
    test "${hypo_n:-0}" = "1"
mon "SELECT hypopg_reset();" >/dev/null

echo "==> [3/5] Seed"
if ! docker compose -f "$COMPOSE_FILE" exec -T monitored-db \
        psql -v ON_ERROR_STOP=1 -q -U "$DB_USER" -d "$MON_DB" <"$REPO_ROOT/demo/seed.sql" >/dev/null; then
    bad "seed script errored"
fi
orders_n="$(mon "SELECT count(*) FROM orders;")"
assert "orders seeded (${orders_n} rows)" test "${orders_n:-0}" -gt 0

echo "==> [4/5] Warmup accumulates pg_stat_statements"
ITERATIONS="${ITERATIONS:-8}" bash "$REPO_ROOT/demo/warmup.sh" >/dev/null
stmts_n="$(mon "SELECT count(*) FROM pg_stat_statements WHERE query ILIKE '%orders%' OR query ILIKE '%events%';")"
assert "pg_stat_statements captured ${stmts_n} demo statements" test "${stmts_n:-0}" -ge 5
calls_n="$(mon "SELECT COALESCE(sum(calls), 0) FROM pg_stat_statements WHERE query ILIKE '%orders%';")"
assert "non-zero calls accumulated (${calls_n})" test "${calls_n:-0}" -gt 0

echo "==> [5/5] Plan shape: the hot-customer query must Seq Scan orders"
plan="$(mon "EXPLAIN (FORMAT JSON) SELECT * FROM orders WHERE customer_id = 1 ORDER BY created_at DESC;")"
if printf '%s' "$plan" | grep -q '"Node Type": "Seq Scan"'; then
    ok "Seq Scan present -- ground truth for Phase 1's orders(customer_id) recommendation"
    echo "        proof node: $(printf '%s' "$plan" | grep -o '"Node Type": "Seq Scan"' | head -n1) on \"orders\""
else
    bad "expected Seq Scan not found in plan"
    printf '%s\n' "$plan" >&2
fi

echo
echo "==> Summary: ${pass} passed, ${fail} failed"
if [ "$fail" -ne 0 ]; then
    echo "SMOKE TEST FAILED"
    exit 1
fi
echo "SMOKE TEST PASSED"
