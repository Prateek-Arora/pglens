# PgLens — Project Status & Tracker

> **Living status doc — the external memory that lets any session recover context fast.** Update the *Current focus* and *Phase tracker* after every meaningful change. This is the first file to read at session start.

_Last updated: 2026-08-24_

## Current focus
**Phase 0 (Foundations & Dev Environment): ✅ DONE.** Reproducible repo, two-instance Docker Compose (monitored: `pg_stat_statements` + `hypopg`; metadata: `pgvector`), a reproducible skewed demo dataset, a documented slow-query oracle (validated with real HypoPG deltas), lint + compose smoke CI, and Apache-2.0 licensing. Smoke test passes 8/8. Tag `v0.0.0-scaffold`. See `docs/phases/phase_0.md`.

**Next action:** user provides the Phase 1 plan (Core Advisor Engine CLI) → run `docs/workflows/plan-verification.md` → then execute. The `demo/slow_queries.sql` oracle + `demo/README.md` HypoPG deltas are the ground truth Phase 1 tests against.

## What PgLens is (one line)
OSS, self-hosted Postgres slow-query & index advisor with HypoPG-validated index recs and local-LLM-explained EXPLAIN plans.

## Goals (ranked)
1. A genuinely **usable, self-hostable tool** a team would run (privacy via local LLM + non-hallucinated HypoPG-validated recs).
2. **Target-skill coverage** for the user's job search: RDBMS tuning (deep), gRPC/Protobuf, RAG/pgvector/LLM, Kubernetes, Next.js, Terraform.
3. A clean OSS launch with **real, measured** benchmarks.

## Scope & plan of record
- **Full arc = Phases 0–7**, but **ship after Phase 4** is the plan of record (complete self-hostable web tool proving the four highest-ROI skills). Phases 5–7 = v0.2.
- Calendar estimate: **~24–34 weeks at ~10 hrs/week** (re-baselined; per-phase build estimates are ~1.5–2× optimistic since gRPC/RAG/K8s/Terraform start from zero).
- Shippable checkpoints: **Phase 1** `v0.0.1` (CLI) · **Phase 4** `v0.1.0-rc` (web tool) · **Phase 7** `v0.1.0` (public).

## "Actually usable" acceptance bar (applies to every phase)
1. Read-only & safe by default. 2. One-command self-host (`docker compose up`). 3. No fabricated numbers. 4. Works against a real DB in < 10 min from cold clone. 5. Degrades gracefully (no LLM → still deterministic analysis).

## Phase tracker
| Phase | Title | Status | Ships | Checkpoint |
|---|---|---|---|---|
| −1 | Setup: AI-context system | ✅ done | Docs/memory/token system | — |
| 0 | Foundations & Dev Environment | ✅ done | Repo, Compose, seeded slow demo DB, green CI | `v0.0.0-scaffold` |
| 1 | Core Advisor Engine (CLI) — MVP | ⬜ | `pglens scan`: slow queries + plan + HypoPG-validated rec | `v0.0.1` |
| 2 | Collector Agent + Server + History | ⬜ | gRPC agent → server persists snapshots → trends | — |
| 3 | RAG + LLM Explanation Layer | ⬜ | Grounded plain-language explanations (local LLM, guardrailed) | — |
| 4 | Dashboard (Next.js) | ⬜ | Leaderboard, plan viewer, recs, trends (+opt GraphQL) | `v0.1.0-rc` ← **ship here** |
| 5 | Containerize + Kubernetes + Helm | ⬜ (v0.2) | `helm install pglens` on kind/k3d | — |
| 6 | IaC + Cloud Deploy + Hardening | ⬜ (v0.2) | Terraform → free-tier + Neon; self-observability; opt OIDC | — |
| 7 | Launch & Community | ⬜ (v0.2) | README, benchmarks, demo, `v0.1.0`, launch drafts | `v0.1.0` |

Legend: ⬜ not started · 🟡 in progress · ✅ done · ⏸ paused

## Open questions / to confirm
- Exact Java + Spring Boot versions — **deferred to Phase 1** (no Java in Phase 0; ADR-0010). Note: host has JDK 17; Phase 1 will use a Gradle toolchain to pin 21 without a manual install.
- ~~Whether to `git init` now~~ — **done** (Phase 0, `main` branch; ADR-0011 context).
- GraphQL read track (Phase 4 optional) — only claim the skill if that track is completed.
- Phase 1: revisit `pg_stat_statements.track=top` vs `track=all` for the ranker (track=all captures FK-check triggers; see ADR-0011).

## Session log (newest first — one line each, keep it short)
- 2026-08-24 — **Phase 0 shipped.** Compose (monitored + metadata) w/ hypopg (PGDG) + pgvector pinned; reproducible skewed seed (~1M rows); slow-query oracle validated with real HypoPG deltas; lint + compose-smoke CI; Apache-2.0; git init on `main`; smoke 8/8. ADR-0009/0010/0011. Tag `v0.0.0-scaffold`.
- 2026-08-22 — Set up AI-context system: `CLAUDE.md`, `docs/` (project, architecture, decisions, phases, workflows, glossary), `.claude/rules/`, memory index. Researched 2026 best practices first.
