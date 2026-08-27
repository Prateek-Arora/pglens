# PgLens — Claude Operating Guide

> **Lean entry point, loaded every session.** Keep this < 200 lines. Detailed context lives in `docs/` and is read on demand — do **not** inline it here. Every line here spends context budget on every turn.

## What PgLens is
Open-source, self-hosted **Postgres slow-query & index advisor** with **LLM-explained EXPLAIN plans**. A lightweight collector agent (Java/Spring) reads `pg_stat_statements` from a monitored Postgres and streams stats over **gRPC** to a Spring Boot server. The server ranks slow queries, captures plans, detects anti-patterns, and **validates index recommendations against the real planner via HypoPG** (real cost delta, not a fabricated number). A local LLM (**Ollama**) explains the plan in plain language. A **Next.js** dashboard shows leaderboard, plan viewer, recs, and trends. Runs entirely on free/local infra.

## Read the doc you need (progressive disclosure — don't read all of them)
- **`docs/project.md`** — goals, scope, status, phase tracker, **Current focus** ← check FIRST each session
- **`docs/architecture.md`** — end-state architecture, components, deliberate design choices, skill-coverage map
- **`docs/decisions.md`** — decision log (the *why*); read before revisiting or contradicting a past decision
- **`docs/backlog.md`** — consciously-deferred work (the *why-not-yet* + contributor tags); check before proposing a "new" engine feature
- **`docs/phases/`** — per-phase plans (each self-contained); `README.md` indexes them
- **`docs/workflows/plan-verification.md`** — how we turn a user-provided phase plan into a thorough, verified plan
- **`docs/glossary.md`** — domain terms (HypoPG, pg_stat_statements, EXPLAIN, RAG, etc.)

## Tech stack (confirm/pin exact versions in Phase 0, then update `docs/architecture.md`)
- Collector agent + server: **Java 21 + Spring Boot** `[ASSUMPTION: Java 21 until Phase 0 pins it]`
- Ingest: **gRPC / Protobuf** (server-streaming). API: **REST** (+ optional **GraphQL** read track, Phase 4)
- Metadata store: **Postgres + pgvector** (separate from the monitored DB — never one instance)
- LLM: **Ollama** (local), **RAG** over Postgres docs
- Dashboard: **Next.js** (App Router)
- Deploy: **Docker Compose** (dev) → **Helm** on kind/k3d → **Terraform** + Neon (cloud)

## Core principles (never violate — from the project charter)
1. **No fabricated evidence.** Every number is real, measured, and labeled (HypoPG cost-*estimate* vs actual runtime). Resume/benchmark claims stay templated until real numbers exist.
2. **Safe-by-default vs the monitored DB.** Read-only role; never write to it; no `EXPLAIN ANALYZE` on write statements; hypothetical indexes only (dropped in-session); statement timeouts.
3. **Deterministic core, optional AI.** The tool must produce correct analysis with the LLM turned off. LLM only phrases facts the deterministic core owns.
4. **Ship each phase.** Every phase ends in something runnable and demoable. Plan of record: **ship after Phase 4**; Phases 5–7 = v0.2.
5. **External actions are drafted, not taken.** Launch posts, releases, publishing → prepared as drafts/checklists for explicit go-ahead.
6. **HypoPG honesty.** Validate btree/brin/hash/bloom + partial. GIN/GiST recs (LIKE `%…%`, jsonb, full-text) are surfaced but labeled **"not planner-validated."** Never pretend HypoPG simulated an access method it can't.

## Engineering standards
- **SOLID, DRY, YAGNI, KISS.** Clarity over cleverness. Build what the current phase needs, not speculative generality.
- Match surrounding code style. **Linters/formatters are the source of truth for style** — don't restate style rules in docs.
- Tests accompany logic. Every phase has a **Definition of Done** + a **Verification** section.
- Tag anything unverified with `[ASSUMPTION]`.

## Session protocol (how we work)
1. **Start:** read `docs/project.md` → *Current focus* to know where we are.
2. **New phase plan from user →** run `docs/workflows/plan-verification.md` (verify **before** planning; do not jump to implementation).
3. **During work:** record each real decision in `docs/decisions.md` (context, choice, alternatives, consequences) as it's made.
4. **After a meaningful change:** update `docs/project.md` (status + tracker); update `docs/architecture.md` if structure changed.
5. **Durable, non-obvious learnings →** Claude auto-memory (`MEMORY.md` index), *not* repo docs. Keep the two systems separate: repo docs = authored project truth; memory = accumulated learnings.
6. The user maintains a **teacher skill** that draws on `docs/decisions.md` and `docs/architecture.md` — so keep the *why* in those files rich and current.

## Boundaries
- **Do not** commit or push unless asked. (Repo is not yet git-init'd — flag when appropriate.)
- **Do not** run destructive commands against any database. The monitored DB is **read-only, always**.
- **Do not** inline large context into this file — link to `docs/`.
- **Do not** add gratuitous tech (Kafka, Cassandra/DynamoDB, ElasticSearch) — explicitly out of scope.
