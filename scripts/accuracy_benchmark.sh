#!/usr/bin/env bash
#
# PgLens accuracy benchmark (Phase 2.5, ADR-0038/0039) — how right are PgLens's index recommendations
# on a workload it did NOT write? Two workloads (WORKLOAD=tpch, the default, or WORKLOAD=job):
#
#   tpch  TPC-H-derived: build dbgen/qgen (gregrahn/tpch-kit, pinned) in a throwaway gcc container,
#         generate SF data + several qgen instances per query. Uniform data by design.
#   job   Join Order Benchmark (Leis et al., PVLDB 2015): the real, skewed IMDB snapshot from CWI
#         (downloaded once into build/accuracy-cache, not vendored) + the 113 JOB queries
#         (gregrahn/join-order-benchmark, pinned). Pre-registered in docs/benchmarks.md.
#
# Then, for either workload:
#   1. Load into a THROWAWAY monitored-image container with PRIMARY KEYS ONLY.
#   2. Replay the query instances so pg_stat_statements accumulates real stats.
#   3. Run `pglens scan --json` against it as the read-only pglens_ro role — exactly how PgLens
#      runs against a real database.
#   4. For every planner-validated recommendation, build the index for real on this throwaway copy
#      and measure the query's instances before/after (scripts/accuracy/measure.py).
#
# TPC-H-derived workload is NOT an official TPC result. Measured numbers are from this machine and run
# only; PgLens's numbers are labeled planner estimates. Transcribe the output into docs/benchmarks.md.
#
# Usage:   bash scripts/accuracy_benchmark.sh               # TPC-H, then tear down
#          WORKLOAD=job bash scripts/accuracy_benchmark.sh  # JOB/IMDB (≈ 3.7 GB of CSV; hours)
#          KEEP=1 ...                                       # leave the container up to poke at
# Env overrides: SF (TPC-H scale factor, 0.1), SEEDS (qgen seeds per query, 3), REPEAT (runs of each
# instance for pg_stat_statements; tpch 2, job 1), RUNS (timed runs per measurement, 3), TOP (statements
# PgLens analyzes; tpch 60, job 150), MEASURE_SETTINGS (server, the default | pinned — only to
# reproduce the 2026-09-24 TPC-H table; see measure.py), IMAGE, CONTAINER.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$REPO_ROOT/scripts/accuracy"
WORKLOAD="${WORKLOAD:-tpch}"
CACHE="$REPO_ROOT/build/accuracy-cache"

TPCH_KIT_COMMIT="852ad0a5ee31ebefeed884cea4188781dd9613a3"   # gregrahn/tpch-kit, pinned
JOB_COMMIT="a39603662e023e449cb2121997a5034df9e02ebf"        # gregrahn/join-order-benchmark, pinned
IMDB_URL="http://event.cwi.nl/da/job/imdb.tgz"               # the May 2013 snapshot the JOB paper used
SF="${SF:-0.1}"
SEEDS="${SEEDS:-3}"
RUNS="${RUNS:-3}"
IMAGE="${IMAGE:-pglens/monitored-db:0.0.0}"
PGUSER="pglens"
QUERIES="1 2 3 4 5 6 7 8 9 10 11 12 13 14 16 17 18 19 20 21 22"   # Q15 is a view DDL — excluded

case "$WORKLOAD" in
  tpch) WORK="${WORK:-$REPO_ROOT/build/accuracy}"; PGDB="tpch"; REPEAT="${REPEAT:-2}"; TOP="${TOP:-60}"
        CONTAINER="${CONTAINER:-pglens-accuracy}" ;;
  job)  WORK="${WORK:-$REPO_ROOT/build/accuracy-job}"; PGDB="imdb"; REPEAT="${REPEAT:-1}"; TOP="${TOP:-150}"
        CONTAINER="${CONTAINER:-pglens-accuracy-job}" ;;
  *)    echo "WORKLOAD must be tpch or job (got '$WORKLOAD')" >&2; exit 2 ;;
esac

psqlq() { docker exec -i "$CONTAINER" psql -X -qtA -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDB" "$@"; }

cleanup() {
  if [ "${KEEP:-0}" = "1" ]; then
    echo "KEEP=1 — leaving '$CONTAINER' up (host port $(docker port "$CONTAINER" 5432 2>/dev/null || echo '?'))."
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

start_db() { # $1 = host directory with the data files, mounted read-only at /data
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  # --shm-size: Docker's default 64 MB /dev/shm is too small for parallel hash joins on JOB.
  docker run -d --name "$CONTAINER" --shm-size=1g -e POSTGRES_USER="$PGUSER" -e POSTGRES_PASSWORD=pglens \
    -e POSTGRES_DB="$PGDB" -p 127.0.0.1::5432 -v "$1:/data:ro" "$IMAGE" \
    postgres -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.max=5000 >/dev/null
  for _ in $(seq 1 60); do
    docker exec "$CONTAINER" pg_isready -q -U "$PGUSER" -d "$PGDB" >/dev/null 2>&1 && break; sleep 1
  done
  psqlq < "$REPO_ROOT/deploy/compose/monitored/initdb/00_extensions.sql"
}

finish_load() {
  psqlq -c "VACUUM ANALYZE"
  psqlq < "$REPO_ROOT/deploy/compose/monitored/initdb/20_pglens_ro.sql"
  echo "    rows: $(psqlq -c "SELECT string_agg(relname || '=' || n_live_tup, ' ' ORDER BY relname) FROM pg_stat_user_tables")"
}

prepare_tpch() {
  echo "==> 1. Building tpch-kit @ ${TPCH_KIT_COMMIT:0:7} and generating SF=$SF data + $SEEDS instance(s)/query"
  mkdir -p "$WORK/data" "$WORK/raw"
  docker run --rm -v "$WORK:/out" -e SF="$SF" -e SEEDS="$SEEDS" -e QUERIES="$QUERIES" \
    -e COMMIT="$TPCH_KIT_COMMIT" gcc:13 bash -c '
      set -e
      git clone -q https://github.com/gregrahn/tpch-kit /src
      git -C /src checkout -q "$COMMIT"
      cd /src/dbgen && make -s MACHINE=LINUX DATABASE=POSTGRESQL >/dev/null 2>&1
      ./dbgen -q -f -s "$SF"
      for t in *.tbl; do sed "s/|$//" "$t" > "/out/data/$t"; done
      for q in $QUERIES; do
        for s in $(seq 1 "$SEEDS"); do
          DSS_QUERY=queries ./qgen -s "$SF" -r "$((q * 100 + s))" "$q" > "/out/raw/q${q}_s${s}.sql"
        done
      done'
  python3 "$HERE/prepare_queries.py" "$WORK/raw" "$WORK/queries"

  echo "==> 2. Starting throwaway $IMAGE as '$CONTAINER' and loading (primary keys only)"
  start_db "$WORK/data"
  psqlq < "$HERE/tpch_schema.sql"
  for t in region nation part supplier partsupp customer orders lineitem; do
    psqlq -c "\\copy $t FROM '/data/$t.tbl' WITH (DELIMITER '|')"
  done
  finish_load
}

prepare_job() {
  echo "==> 1. Fetching the IMDB snapshot (cached) + JOB queries @ ${JOB_COMMIT:0:7}"
  mkdir -p "$CACHE"
  if [ ! -f "$CACHE/imdb.tgz" ]; then
    curl -fsSL --retry 3 -o "$CACHE/imdb.tgz.part" "$IMDB_URL" && mv "$CACHE/imdb.tgz.part" "$CACHE/imdb.tgz"
  fi
  if [ ! -f "$CACHE/imdb/.extracted" ]; then
    rm -rf "$CACHE/imdb" && mkdir -p "$CACHE/imdb"
    tar -xzf "$CACHE/imdb.tgz" -C "$CACHE/imdb" && touch "$CACHE/imdb/.extracted"
  fi
  echo "    imdb.tgz sha256 $(shasum -a 256 "$CACHE/imdb.tgz" | cut -c1-16)…"
  git clone -q https://github.com/gregrahn/join-order-benchmark "$WORK/job"
  git -C "$WORK/job" checkout -q "$JOB_COMMIT"
  mkdir -p "$WORK/queries"
  cp "$WORK"/job/[0-9]*.sql "$WORK/queries/"
  echo "    $(find "$WORK/queries" -name '*.sql' | wc -l | tr -d ' ') JOB queries"

  echo "==> 2. Starting throwaway $IMAGE as '$CONTAINER' and loading IMDB (primary keys only)"
  start_db "$CACHE/imdb"
  psqlq < "$WORK/job/schema.sql"   # PKs only — fkindexes.sql is deliberately not applied
  for f in "$CACHE"/imdb/*.csv; do
    t="$(basename "$f" .csv)"
    psqlq -c "\\copy $t FROM '/data/$t.csv' WITH (FORMAT csv, ESCAPE '\\')"
  done
  finish_load
}

docker info >/dev/null 2>&1 || { echo "Docker daemon not running — start Docker Desktop." >&2; exit 1; }
docker image inspect "$IMAGE" >/dev/null 2>&1 \
  || { echo "image $IMAGE not found — build it: docker build -t $IMAGE deploy/compose/monitored" >&2; exit 1; }
rm -rf "$WORK" && mkdir -p "$WORK"
"prepare_$WORKLOAD"

echo "==> 3. Replaying the workload ($REPEAT run(s) of each instance) into pg_stat_statements"
psqlq -c "SELECT pg_stat_statements_reset()" >/dev/null
for f in "$WORK"/queries/*.sql; do
  for _ in $(seq 1 "$REPEAT"); do
    if ! { echo "SET statement_timeout = '600s';"; cat "$f"; } | psqlq >/dev/null 2>"$WORK/last_error.txt"; then
      echo "    ! $(basename "$f") failed: $(head -1 "$WORK/last_error.txt")"
      break
    fi
  done
done

PORT="$(docker port "$CONTAINER" 5432 | head -1 | awk -F: '{print $NF}')"
echo "==> 4. pglens scan --json (as pglens_ro, read-only) on localhost:$PORT"
(cd "$REPO_ROOT" && ./gradlew -q :cli:bootRun --args="scan postgresql://pglens_ro:pglens_ro@localhost:$PORT/$PGDB --json --top $TOP --min-calls 1") \
  > "$WORK/pglens.json"

echo "==> 5. Building each validated index for real and measuring (median of $RUNS warm runs)"
python3 "$HERE/measure.py" "$CONTAINER" "$PGDB" "$PGUSER" "$WORK/queries" "$WORK/pglens.json" \
  "$RUNS" "$WORK/results.md" "$WORK/results.json"
echo
echo "Results: $WORK/results.md (+ results.json, pglens.json)"
