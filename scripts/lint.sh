#!/usr/bin/env bash
#
# Lint the artifacts Phase 0 actually ships: shell, the Dockerfile, and the
# pure-SQL files. Each linter is optional locally (skipped with a note if not
# installed); CI installs and enforces all three.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1
status=0
have() { command -v "$1" >/dev/null 2>&1; }

if have shellcheck; then
    echo "== shellcheck =="
    shellcheck demo/warmup.sh scripts/smoke_test.sh scripts/lint.sh scripts/dogfood_benchmark.sh || status=1
else
    echo "(shellcheck not installed -- skipping)"
fi

if have hadolint; then
    echo "== hadolint =="
    hadolint \
        deploy/compose/monitored/Dockerfile \
        deploy/compose/agent/Dockerfile \
        deploy/compose/server/Dockerfile || status=1
else
    echo "(hadolint not installed -- skipping)"
fi

# sqlfluff is scoped to pure-SQL files only. seed.sql is excluded because it is
# driven as a psql script and its dollar-quoted DO block is not plain SQL.
if have sqlfluff; then
    echo "== sqlfluff =="
    sqlfluff lint \
        deploy/compose/monitored/initdb/00_extensions.sql \
        deploy/compose/monitored/initdb/10_schema.sql \
        deploy/compose/metadata/initdb/00_extensions.sql \
        demo/slow_queries.sql || status=1
else
    echo "(sqlfluff not installed -- skipping)"
fi

if [ "$status" -eq 0 ]; then
    echo "lint: OK"
else
    echo "lint: FAILED"
fi
exit "$status"
