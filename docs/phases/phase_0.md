# Phase 0 — Foundations & Dev Environment

**Status:** ✅ Done (2026-08-24) · **Ships:** reproducible repo + seeded reliably-slow demo Postgres + green CI · **Tag:** `v0.0.0-scaffold`
**Prereqs:** none (start of project). Verified via `../workflows/plan-verification.md` (ADR-0008).

---

## 1. Context & prerequisites (self-contained)

PgLens is an OSS, self-hosted Postgres slow-query & index advisor. Phase 0 builds
the load-bearing base every later phase needs: a clean, reproducible project and a
database that is **predictably slow in known ways** so analysis code has ground
truth to test against. No analysis logic yet (Phase 1), no agent/gRPC (Phase 2),
no LLM (Phase 3), no UI (Phase 4).

## 2. Verification of the user's plan (what was checked / corrected)

Load-bearing facts checked before building:

- **hypopg image (plan's #1 risk) — confirmed & solved.** Stock `postgres:16`
  ships `pg_stat_statements` (contrib) but not hypopg. `postgresql-16-hypopg`
  exists in the PGDG apt repo, which the official Debian-bookworm image already
  configures. A 2-line `Dockerfile` installs it. Verified in the build:
  `postgresql-16-hypopg 1.4.3` installs cleanly.
- **pgvector — pinned.** `pgvector/pgvector:pg16` exists; we pin the exact
  `0.8.6-pg16` (reproducibility + the ≥0.8.2 CVE-2026-3172 fix) rather than the
  rolling tag.
- **Local env gaps that shaped the plan:** host had JDK 17 (not 21), no `gradle`,
  no `psql`, Docker daemon stopped, repo not git-init'd. Consequences: scripts run
  psql **inside the container** (no host `psql` dependency); Java tooling deferred
  (see below); `make up` preflights the daemon.
- **No conflict with ADR-0003** (two Postgres instances) or any locked decision.

Blocking decisions resolved with the user (→ ADR-0009/0010/0011):
license = **Apache-2.0**; Phase-0 tooling = **lint artifacts + compose smoke**
(defer Gradle/Spotless/Testcontainers to Phase 1 — no Java exists yet); **git init
now, scaffold commit + tag at phase end.**

## 3. What was built

```
deploy/compose/   docker-compose.yml (2 pinned services, healthchecks, --wait)
  monitored/      Dockerfile (postgres:16-bookworm + hypopg) + initdb/ (extensions, schema)
  metadata/       initdb/ (pgvector)
demo/             seed.sql (idempotent, reproducible skew), slow_queries.sql (oracle), warmup.sh, README.md
scripts/          smoke_test.sh (reproducibility gate + oracle), lint.sh
.github/workflows ci.yml (lint job + compose smoke job)
Makefile · .editorconfig · .env.example · .sqlfluff · LICENSE(Apache-2.0) · README.md · CONTRIBUTING.md
```

Design highlights:
- **Two Postgres instances**, distinct host ports (5433/5434), named volumes,
  TCP healthchecks (so `--wait` only passes on the real server, not initdb).
- **Schema with PKs + FKs but deliberately-missing secondary indexes.** A FK does
  not index its child column — the anti-pattern PgLens must detect.
- **Reproducible skew** via `setseed(0.42)`: hot customers (power-law), recent-heavy
  timestamps, lopsided status, rare `event_type`. ~1M rows, seeds in ~5s.
- **Oracle grounded in measured HypoPG deltas**, not assumptions (see §Verification).

## 4. Deliberately skipped (YAGNI) + resources

- **Skipped:** Gradle/Spotless/Testcontainers (→ Phase 1, when Java lands), a
  read-only monitored role (→ Phase 2, when the agent connects), Docker layer
  caching in CI, any analysis/agent/LLM/UI code.
- **New-skill resources (fast path):** `pg_stat_statements`
  (postgresql.org/docs → enabling via preload + the `calls`/`total_exec_time`
  columns); HypoPG (github.com/HypoPG/hypopg → `hypopg_create_index` + `hypopg_reset`);
  pgvector README (getting-started only).

## 5. Definition of Done — all met ✅

- [x] `make up && make seed && make warmup && make test` works from a cold clone.
- [x] `monitored-db` has `pg_stat_statements` + `hypopg`; `metadata-db` has `vector` (asserted).
- [x] After warmup, `pg_stat_statements` holds the demo queries with non-zero calls.
- [x] `slow_queries.sql` documents each query's expected problem (the Phase 1 oracle).
- [x] CI defined: `lint` (shellcheck + hadolint + sqlfluff) + `smoke` (compose end-to-end).
- [x] README quickstart reproduces it in < 10 min; roadmap linked.
- [x] Tag `v0.0.0-scaffold`.

## 6. Verification (how it was proven)

- **Smoke test: 8/8 passed** — extensions present, hypopg functional (hypothetical
  index round-trip), seed idempotency guard, data survives container recreation,
  and a real `Seq Scan` on the flagship query (§10 self-check satisfied: the proof
  node is printed).
- **Oracle validated with real HypoPG cost deltas** (in-session): every
  recommendation produces a measured win, from ~99.7% (query 2) down to two honest
  negatives — query 8 (~0.3%, cost is in the fact-table scan) and query 10 (0%,
  legitimate full scan). Table in `demo/README.md`.
- **Explained anomaly (no unexplained numbers):** `sum(calls)` was ~500k because
  `pg_stat_statements.track=all` captures the per-row FK-check trigger during the
  `order_items` bulk load. Real, benign, and a flag for Phase 1 (revisit
  `track=top` vs `track=all` for the ranker).

## 7. Durable learnings (→ Claude memory)

- **HypoPG hypothetical indexes are session-local** — the Phase 1 validator must
  run create-index + `EXPLAIN` + reset on a single held connection.
- Local dev facts (JDK 17, no gradle/psql, Homebrew present) — see memory.
