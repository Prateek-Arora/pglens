#!/usr/bin/env bash
# Dashboard end-to-end smoke (Phase 4B, ADR-0046): the full compose stack with the demo database,
# its agent registered through the API, a warmed-up workload, then Playwright + axe against the
# dashboard. Leaves the stack running (CI tears it down; locally `make down`).
#
# Needs Node 24 + pnpm (corepack) and Playwright's Chromium:
#   cd dashboard && pnpm install && pnpm exec playwright install --no-shell chromium
# (or PLAYWRIGHT_CHANNEL=chrome to use an installed Chrome).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/deploy/compose/.env"

make -C "$ROOT" up
make -C "$ROOT" seed
make -C "$ROOT" register
# Two workload passes one sampling interval apart: a query's first sighting only anchors its
# counters (ADR-0024), so activity shows from the second sample on.
make -C "$ROOT" warmup
sleep 20
make -C "$ROOT" warmup

PGLENS_ADMIN_PASSWORD="$(grep '^PGLENS_ADMIN_PASSWORD=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
export PGLENS_ADMIN_PASSWORD
cd "$ROOT/dashboard"
pnpm exec playwright test
